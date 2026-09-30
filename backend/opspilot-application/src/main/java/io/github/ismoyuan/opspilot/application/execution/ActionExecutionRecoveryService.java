package io.github.ismoyuan.opspilot.application.execution;

import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRepository.ExecutionRecord;
import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector.Inspected;
import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector.NotInspected;
import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector.RuntimeInspection;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.DataSourceConnectionRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.timeline.ActionExecutionEventPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RUNNING Execution 的有界只读核对（08 TASK-072、04 §82、07 §66～§67）：restart 结果未知，或 RUNNING 没有 Worker（进程重启、结果落账失败）
 * 时进入。只依赖只读 {@link ServiceRuntimeInspector}，绝不重发 CHANGE；不创建 Invocation/Observation、不调用 AI、不扣调查预算。
 *
 * <p>每次尝试：
 * <ol>
 *   <li>距上次登记不足间隔则在事务外等待（不超过已冻结的截止时间）。
 *   <li>登记短事务：锁 Incident（须 EXECUTING），重读 Execution 仍为 RUNNING；次数已达快照上限或截止时间已到 → FAILED /
 *       EXECUTION_RESULT_UNCERTAIN、方案 EXECUTED、Incident → DIAGNOSED；否则条件更新次数加一、写尝试时间、首次冻结截止时间，并写
 *       ACTION_EXECUTION_RECONCILIATION_ATTEMPTED，提交。
 *   <li>提交后在事务外以执行准入时解析的容器 id inspect，单次超时受信配置且不超过截止时间。
 *   <li>结果短事务：同一容器、RUNNING 且启动时间晚于 execution.started_at → SUCCEEDED（核对结果载荷）、方案 EXECUTED、
 *       ACTION_EXECUTION_SUCCEEDED（Incident 仍 EXECUTING，Verification 属 TASK-080）；否则不猜测成功——没有剩余次数或已到期即按上面
 *       的 UNCERTAIN 收束，还有额度则进入下一次尝试。
 * </ol>
 * 次数、上限与截止时间都在数据库中，重启不刷新：登记后崩溃只消耗该次，只要次数与期限仍允许就继续只读核对。同一 Execution 的单飞由派发器
 * 保证（07 §51），并发的登记与落账另有状态/版本条件更新兜底。
 */
@Service
public class ActionExecutionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionRecoveryService.class);

    private final ActionExecutionRepository executions;
    private final IncidentRepository incidents;
    private final DataSourceConnectionRepository connections;
    private final ServiceRuntimeInspector inspector;
    private final SchemaCodecRegistry codecs;
    private final ExecutionEvents events;
    private final ExecutionSettings settings;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ActionExecutionRecoveryService(
            ActionExecutionRepository executions,
            IncidentRepository incidents,
            DataSourceConnectionRepository connections,
            ServiceRuntimeInspector inspector,
            SchemaCodecRegistry codecs,
            TimelineRepository timeline,
            ExecutionSettings settings,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.executions = executions;
        this.incidents = incidents;
        this.connections = connections;
        this.inspector = inspector;
        this.codecs = codecs;
        this.events = new ExecutionEvents(timeline);
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 核对到 Execution 离开 RUNNING、额度用尽或线程被中断为止；非 RUNNING 直接返回。 */
    public void reconcile(long executionId) {
        while (true) {
            Optional<ExecutionRecord> found = executions.findById(executionId);
            if (found.isEmpty() || found.get().status() != ActionExecutionStatus.RUNNING) {
                return;
            }
            if (!awaitNextAttempt(found.get())) {
                return;
            }
            Optional<ExecutionRecord> registered = transaction.execute(status -> register(found.get()));
            if (registered == null || registered.isEmpty()) {
                return;
            }
            ExecutionRecord attempt = registered.get();
            RuntimeInspection inspection = inspect(attempt);
            Boolean settled = transaction.execute(status -> settle(attempt, inspection));
            if (Boolean.TRUE.equals(settled)) {
                return;
            }
        }
    }

    /**
     * 距上次登记不足间隔时等待；不越过已冻结的截止时间（到期后由登记事务收束）。
     *
     * @return 线程被中断时为 false：不再核对，剩余额度由补派发或下次启动继续
     */
    private boolean awaitNextAttempt(ExecutionRecord execution) {
        if (execution.lastReconciliationAt() == null) {
            return true;
        }
        Instant next = execution.lastReconciliationAt().plus(settings.reconciliationInterval());
        if (execution.reconciliationDeadlineAt() != null && next.isAfter(execution.reconciliationDeadlineAt())) {
            next = execution.reconciliationDeadlineAt();
        }
        Duration wait = Duration.between(clock.instant(), next);
        if (wait.isNegative() || wait.isZero()) {
            return true;
        }
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("Reconciliation interrupted, remaining attempts resume later: executionId={}", execution.id());
            return false;
        }
    }

    /**
     * 登记事务：返回值非空才可以在提交后发出 inspect。
     *
     * @return 已登记本次尝试的记录（次数、尝试时间与截止时间为登记后的值）
     */
    private Optional<ExecutionRecord> register(ExecutionRecord seen) {
        Incident incident = incidents
                .findByIdForUpdate(seen.incidentId())
                .orElseThrow(() -> new IllegalStateException("Execution without incident: " + seen.id()));
        ExecutionRecord current = executions.findById(seen.id()).orElseThrow();
        if (current.status() != ActionExecutionStatus.RUNNING) {
            return Optional.empty();
        }
        if (incident.status() != IncidentStatus.EXECUTING) {
            log.warn(
                    "Running execution of an incident not EXECUTING, not reconciled: executionId={} status={}",
                    current.id(),
                    incident.status());
            return Optional.empty();
        }
        Instant now = now();
        if (exhausted(current, now)) {
            failUncertain(incident, current, now);
            return Optional.empty();
        }
        Instant deadline = current.reconciliationDeadlineAt() != null
                ? current.reconciliationDeadlineAt()
                : now.plus(settings.reconciliationMaxDuration());
        if (!executions.registerReconciliation(current.id(), current.lockVersion(), now, deadline)) {
            return Optional.empty();
        }
        int attemptNo = current.reconciliationAttemptCount() + 1;
        events.reconciliationAttempted(incident, current, attemptNo, now);
        return Optional.of(new ExecutionRecord(
                current.id(),
                current.status(),
                current.lockVersion() + 1,
                current.remediationActionId(),
                current.planId(),
                current.incidentId(),
                current.executionContextPayload(),
                current.startedAt(),
                attemptNo,
                current.maxReconciliationAttempts(),
                now,
                deadline));
    }

    /** 事务外只读检查；单次超时不超过已冻结的截止时间。 */
    private RuntimeInspection inspect(ExecutionRecord attempt) {
        ServiceRestartExecutionContextV1 context = codecs.decode(
                ServiceRestartExecutionContextV1.SCHEMA_NAME,
                ServiceRestartExecutionContextV1.SCHEMA_VERSION,
                attempt.executionContextPayload(),
                ServiceRestartExecutionContextV1.class);
        if (context.containerId() == null) {
            return new NotInspected(ErrorCode.INVALID_BINDING, "Execution context has no admitted container");
        }
        Optional<DataSourceConnection> connection = connections.findById(context.dataSourceConnectionId());
        if (connection.isEmpty()) {
            return new NotInspected(ErrorCode.INVALID_BINDING, "Docker connection not found");
        }
        Instant deadline = clock.instant().plus(settings.reconciliationTimeout());
        if (deadline.isAfter(attempt.reconciliationDeadlineAt())) {
            deadline = attempt.reconciliationDeadlineAt();
        }
        return inspector.inspect(connection.get(), context.containerId(), deadline);
    }

    /**
     * 结果事务。
     *
     * @return 是否已结束核对（确认成功、UNCERTAIN 收束，或 Execution 已不再由本次登记持有）
     */
    private boolean settle(ExecutionRecord attempt, RuntimeInspection inspection) {
        Incident incident = incidents.findByIdForUpdate(attempt.incidentId()).orElseThrow();
        Instant now = now();
        ServiceRestartExecutionContextV1 context = codecs.decode(
                ServiceRestartExecutionContextV1.SCHEMA_NAME,
                ServiceRestartExecutionContextV1.SCHEMA_VERSION,
                attempt.executionContextPayload(),
                ServiceRestartExecutionContextV1.class);
        Optional<Instant> startedAfterExecution = confirmedStart(attempt, context, inspection);
        if (startedAfterExecution.isPresent()) {
            ServiceRestartReconciliationResultV1 result = new ServiceRestartReconciliationResultV1(
                    ServiceRestartResultV1.DOCKER,
                    context.containerId(),
                    attempt.startedAt(),
                    startedAfterExecution.get(),
                    now,
                    attempt.reconciliationAttemptCount());
            if (!executions.markSucceeded(
                    attempt.id(),
                    attempt.lockVersion(),
                    ServiceRestartReconciliationResultV1.SCHEMA_NAME,
                    ServiceRestartReconciliationResultV1.SCHEMA_VERSION,
                    codecs.encode(
                            ServiceRestartReconciliationResultV1.SCHEMA_NAME,
                            ServiceRestartReconciliationResultV1.SCHEMA_VERSION,
                            result),
                    now)) {
                return true;
            }
            executions.markPlanExecuted(attempt.planId(), now);
            events.append(
                    incident,
                    TimelineEventType.ACTION_EXECUTION_SUCCEEDED,
                    "经只读核对确认重启操作已生效",
                    attempt,
                    ActionExecutionEventPayloadV1.EXECUTION,
                    null,
                    now);
            return true;
        }
        log.info(
                "Reconciliation attempt did not confirm the restart: executionId={} attempt={}/{} observation={}",
                attempt.id(),
                attempt.reconciliationAttemptCount(),
                attempt.maxReconciliationAttempts(),
                describe(inspection));
        if (!exhausted(attempt, now)) {
            return false;
        }
        ExecutionRecord current = executions.findById(attempt.id()).orElseThrow();
        if (current.status() != ActionExecutionStatus.RUNNING || current.lockVersion() != attempt.lockVersion()) {
            return true;
        }
        failUncertain(incident, current, now);
        return true;
    }

    /**
     * 只接受执行准入时解析的同一容器：运行中且启动时间严格晚于 Execution 进入 RUNNING 的时间（04 §82）。身份不符、未运行、时间缺失或
     * 不可比较都不确认；restartCount 不作依据。
     *
     * @return 确认时为观察到的容器启动时间
     */
    private static Optional<Instant> confirmedStart(
            ExecutionRecord attempt, ServiceRestartExecutionContextV1 context, RuntimeInspection inspection) {
        if (inspection instanceof Inspected inspected
                && inspected.containerId().equals(context.containerId())
                && inspected.runtimeState() == RuntimeState.RUNNING
                && inspected.startedAt() != null
                && attempt.startedAt() != null
                && inspected.startedAt().isAfter(attempt.startedAt())) {
            return Optional.of(inspected.startedAt());
        }
        return Optional.empty();
    }

    /** 次数已达快照上限或截止时间已到。 */
    private static boolean exhausted(ExecutionRecord execution, Instant now) {
        return execution.reconciliationAttemptCount() >= execution.maxReconciliationAttempts()
                || (execution.reconciliationDeadlineAt() != null
                        && !now.isBefore(execution.reconciliationDeadlineAt()));
    }

    /** 结果仍未知：FAILED / EXECUTION_RESULT_UNCERTAIN，不声称远端没有发生（04 §82）。 */
    private void failUncertain(Incident incident, ExecutionRecord execution, Instant now) {
        if (!executions.markFailed(
                execution.id(),
                ActionExecutionStatus.RUNNING,
                execution.lockVersion(),
                ErrorCode.EXECUTION_RESULT_UNCERTAIN.name(),
                "Restart result could not be confirmed by read-only reconciliation",
                now)) {
            return;
        }
        executions.markPlanExecuted(execution.planId(), now);
        incidents.apply(incident.transitionFor(IncidentTrigger.EXECUTION_FAILED, incident.version()), now);
        events.append(
                incident,
                TimelineEventType.ACTION_EXECUTION_FAILED,
                "无法确认重启操作是否生效，未再次执行",
                execution,
                ActionExecutionEventPayloadV1.EXECUTION,
                ErrorCode.EXECUTION_RESULT_UNCERTAIN.name(),
                now);
    }

    private static String describe(RuntimeInspection inspection) {
        return switch (inspection) {
            case Inspected inspected -> inspected.runtimeState() + "/" + inspected.startedAt();
            case NotInspected failed -> failed.code().name();
        };
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
