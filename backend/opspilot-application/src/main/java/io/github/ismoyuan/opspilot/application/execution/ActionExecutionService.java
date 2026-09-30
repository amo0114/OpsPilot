package io.github.ismoyuan.opspilot.application.execution;

import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.dispatch.ActionExecutionWorker;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRepository.ExecutionRecord;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor.RestartOutcome;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor.TargetResolution;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.DataSourceConnectionRepository;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityRegistry;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.timeline.ActionExecutionEventPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * service.restart 的执行 Worker（08 TASK-071、04 §79、§82、06 §106）。外部调用都不在数据库事务内：
 * <ol>
 *   <li>只处理 PENDING；RUNNING 不能当作“还没发送”，由核对与启动恢复处理（TASK-072/073），终态直接返回。
 *   <li>事务外按执行上下文的 Docker 连接与容器名只读解析真实容器 id。
 *   <li>准入短事务：锁 Incident（须为 EXECUTING），复核 Execution 仍 PENDING、目标的 service.restart 仍可执行且唯一 Provider 与冻结的
 *       绑定/连接/容器名一致，然后条件更新 PENDING → RUNNING（保存容器 id 与 started_at）并写 ACTION_EXECUTION_STARTED。只有这次
 *       条件更新的获胜者可以发出 CHANGE。准入前任何不满足（含解析失败）都没有发出 CHANGE：Execution FAILED（无 started_at）、方案
 *       CANCELLED、Incident → DIAGNOSED、ACTION_EXECUTION_FAILED（phase=ADMISSION）。
 *   <li>事务外发出一次 restart，不重试。
 *   <li>结果短事务：成功 → SUCCEEDED、方案 EXECUTED、ACTION_EXECUTION_SUCCEEDED（Incident 仍 EXECUTING——以冻结快照创建 Verification
 *       并 → VERIFYING 属 TASK-080 的同一成功事务）；明确失败 → FAILED、方案 EXECUTED、Incident → DIAGNOSED、ACTION_EXECUTION_FAILED；
 *       结果未知 → 不改任何数据，保持 RUNNING 交给有界只读核对（TASK-072），绝不重发。
 * </ol>
 */
@Service
public class ActionExecutionService implements ActionExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionService.class);
    private static final String RESTART = CapabilityKey.SERVICE_RESTART.key();

    private final ActionExecutionRepository executions;
    private final IncidentRepository incidents;
    private final ManagedResourceRepository resources;
    private final DataSourceConnectionRepository connections;
    private final CapabilityAccess access;
    private final CapabilityRegistry registry;
    private final ServiceRestartExecutor executor;
    private final SchemaCodecRegistry codecs;
    private final TimelineRepository timeline;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ActionExecutionService(
            ActionExecutionRepository executions,
            IncidentRepository incidents,
            ManagedResourceRepository resources,
            DataSourceConnectionRepository connections,
            CapabilityAccess access,
            CapabilityRegistry registry,
            ServiceRestartExecutor executor,
            SchemaCodecRegistry codecs,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.executions = executions;
        this.incidents = incidents;
        this.resources = resources;
        this.connections = connections;
        this.access = access;
        this.registry = registry;
        this.executor = executor;
        this.codecs = codecs;
        this.timeline = timeline;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void runActionExecution(long executionId) {
        Optional<ExecutionRecord> found = executions.findById(executionId);
        if (found.isEmpty() || found.get().status() != ActionExecutionStatus.PENDING) {
            log.debug("Execution not pending, no CHANGE is sent: executionId={}", executionId);
            return;
        }
        ExecutionRecord pending = found.get();
        ServiceRestartExecutionContextV1 context = context(pending);
        Optional<DataSourceConnection> connection = connections.findById(context.dataSourceConnectionId());

        TargetResolution resolution = connection.isEmpty()
                ? new ServiceRestartExecutor.Unresolved(ErrorCode.INVALID_BINDING, "Docker connection not found")
                : executor.resolveTarget(
                        connection.get(), context.containerName(), deadline(CapabilityKey.SERVICE_INSPECT));
        Optional<Admitted> admitted = transaction.execute(status -> admit(pending, context, resolution));
        if (admitted == null || admitted.isEmpty()) {
            return;
        }

        RestartOutcome outcome = executor.restart(
                admitted.get().connection(),
                admitted.get().context().containerId(),
                deadline(CapabilityKey.SERVICE_RESTART));
        if (outcome instanceof ServiceRestartExecutor.Uncertain uncertain) {
            log.warn(
                    "Execution result uncertain, left RUNNING for read-only reconciliation: executionId={} code={}",
                    executionId,
                    uncertain.code());
            return;
        }
        transaction.executeWithoutResult(status -> record(admitted.get(), outcome));
    }

    /** 准入获胜后的 RUNNING 记录、已解析容器身份的上下文与准入时确认的 Docker 连接。 */
    private record Admitted(
            ExecutionRecord execution, ServiceRestartExecutionContextV1 context, DataSourceConnection connection) {}

    /** 准入事务：只有返回值非空（条件更新获胜）才可以发出 CHANGE。 */
    private Optional<Admitted> admit(
            ExecutionRecord pending, ServiceRestartExecutionContextV1 context, TargetResolution resolution) {
        Incident incident = incidents
                .findByIdForUpdate(pending.incidentId())
                .orElseThrow(() -> new IllegalStateException("Execution without incident: " + pending.id()));
        ExecutionRecord current = executions.findById(pending.id()).orElseThrow();
        if (current.status() != ActionExecutionStatus.PENDING || current.lockVersion() != pending.lockVersion()) {
            return Optional.empty(); // 另一个 Worker 已处理
        }
        if (incident.status() != IncidentStatus.EXECUTING) {
            log.warn(
                    "Pending execution of an incident not EXECUTING, not admitted: executionId={} status={}",
                    pending.id(),
                    incident.status());
            return Optional.empty();
        }
        Instant now = now();
        if (!(resolution instanceof ServiceRestartExecutor.Resolved resolved)) {
            ServiceRestartExecutor.Unresolved unresolved = (ServiceRestartExecutor.Unresolved) resolution;
            failBeforeStart(incident, current, unresolved.code(), unresolved.message(), now);
            return Optional.empty();
        }
        // 运行时仍检查授权与 Binding（04 §45、06 §107）：不能凭批准时的上下文越过被撤销或已改变的目标
        CapabilityAccess.Decision decision = access.evaluateChange(
                incident.managedSystemId(), resources.findById(context.targetResourceId()), RESTART);
        if (!(decision instanceof CapabilityAccess.Allowed allowed)) {
            ErrorCode code = ((CapabilityAccess.Denied) decision).code();
            failBeforeStart(incident, current, code, "Restart target is no longer executable", now);
            return Optional.empty();
        }
        ServiceRestartExecutionContextV1 frozen =
                ServiceRestartExecutionContextV1.pending(context.targetResourceId(), allowed.provider());
        if (!frozen.equals(context)) {
            failBeforeStart(
                    incident, current, ErrorCode.INVALID_BINDING, "Restart target binding changed after approval", now);
            return Optional.empty();
        }
        ServiceRestartExecutionContextV1 running = new ServiceRestartExecutionContextV1(
                context.targetResourceId(),
                context.resourceBindingId(),
                context.dataSourceConnectionId(),
                context.containerName(),
                resolved.containerId());
        if (!executions.markRunning(current.id(), current.lockVersion(), encode(running), now)) {
            return Optional.empty();
        }
        append(
                incident,
                TimelineEventType.ACTION_EXECUTION_STARTED,
                "开始执行重启操作",
                current,
                ActionExecutionEventPayloadV1.EXECUTION,
                null,
                now);
        ExecutionRecord started = new ExecutionRecord(
                current.id(),
                ActionExecutionStatus.RUNNING,
                current.lockVersion() + 1,
                current.remediationActionId(),
                current.planId(),
                current.incidentId(),
                encode(running),
                now);
        return Optional.of(new Admitted(started, running, allowed.provider().connection()));
    }

    /** 结果事务：只推进仍由本次准入持有的 RUNNING 记录。 */
    private void record(Admitted admitted, RestartOutcome outcome) {
        ExecutionRecord execution = admitted.execution();
        Incident incident = incidents.findByIdForUpdate(execution.incidentId()).orElseThrow();
        Instant now = now();
        switch (outcome) {
            case ServiceRestartExecutor.Succeeded succeeded -> {
                if (!executions.markSucceeded(
                        execution.id(),
                        execution.lockVersion(),
                        ServiceRestartResultV1.SCHEMA_NAME,
                        ServiceRestartResultV1.SCHEMA_VERSION,
                        codecs.encode(
                                ServiceRestartResultV1.SCHEMA_NAME,
                                ServiceRestartResultV1.SCHEMA_VERSION,
                                succeeded.result()),
                        now)) {
                    throw new IllegalStateException("Execution no longer held by this worker: " + execution.id());
                }
                executions.markPlanExecuted(execution.planId(), now);
                append(
                        incident,
                        TimelineEventType.ACTION_EXECUTION_SUCCEEDED,
                        "重启操作执行成功",
                        execution,
                        ActionExecutionEventPayloadV1.EXECUTION,
                        null,
                        now);
            }
            case ServiceRestartExecutor.Failed failed -> {
                if (!executions.markFailed(
                        execution.id(),
                        ActionExecutionStatus.RUNNING,
                        execution.lockVersion(),
                        failed.code().name(),
                        failed.message(),
                        now)) {
                    throw new IllegalStateException("Execution no longer held by this worker: " + execution.id());
                }
                executions.markPlanExecuted(execution.planId(), now);
                incidents.apply(incident.transitionFor(IncidentTrigger.EXECUTION_FAILED, incident.version()), now);
                append(
                        incident,
                        TimelineEventType.ACTION_EXECUTION_FAILED,
                        "重启操作失败",
                        execution,
                        ActionExecutionEventPayloadV1.EXECUTION,
                        failed.code().name(),
                        now);
            }
            case ServiceRestartExecutor.Uncertain ignored ->
                throw new IllegalStateException("uncertain is not recorded");
        }
    }

    /** 准入前失败：没有发出 CHANGE，方案没有执行尝试。 */
    private void failBeforeStart(
            Incident incident, ExecutionRecord execution, ErrorCode code, String message, Instant now) {
        if (!executions.markFailed(
                execution.id(), ActionExecutionStatus.PENDING, execution.lockVersion(), code.name(), message, now)) {
            return;
        }
        executions.markPlanCancelled(execution.planId(), now);
        incidents.apply(incident.transitionFor(IncidentTrigger.EXECUTION_FAILED, incident.version()), now);
        append(
                incident,
                TimelineEventType.ACTION_EXECUTION_FAILED,
                "重启操作未执行：目标已不可执行",
                execution,
                ActionExecutionEventPayloadV1.ADMISSION,
                code.name(),
                now);
    }

    private void append(
            Incident incident,
            TimelineEventType type,
            String summary,
            ExecutionRecord execution,
            String phase,
            String errorCode,
            Instant now) {
        timeline.append(new NewTimelineEvent(
                incident.id(),
                type,
                now,
                TimelineActorType.SYSTEM,
                null,
                summary,
                new ActionExecutionEventPayloadV1(
                        incident.incidentKey().value(),
                        execution.id(),
                        execution.remediationActionId(),
                        phase,
                        errorCode),
                Correlation.currentId()));
    }

    private ServiceRestartExecutionContextV1 context(ExecutionRecord execution) {
        return codecs.decode(
                ServiceRestartExecutionContextV1.SCHEMA_NAME,
                ServiceRestartExecutionContextV1.SCHEMA_VERSION,
                execution.executionContextPayload(),
                ServiceRestartExecutionContextV1.class);
    }

    private String encode(ServiceRestartExecutionContextV1 context) {
        return codecs.encode(
                ServiceRestartExecutionContextV1.SCHEMA_NAME, ServiceRestartExecutionContextV1.SCHEMA_VERSION, context);
    }

    private Instant deadline(CapabilityKey capability) {
        return clock.instant().plus(registry.definition(capability).timeout());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
