package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityArguments;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogsSearchArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.canonical.CanonicalJsonWriter;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityExecutionResult;
import io.github.ismoyuan.opspilot.application.capability.CapabilityExecutionService;
import io.github.ismoyuan.opspilot.application.capability.CapabilityInvocationRepository;
import io.github.ismoyuan.opspilot.application.capability.NewRecoverySampleInvocation;
import io.github.ismoyuan.opspilot.application.capability.ResolvedWindow;
import io.github.ismoyuan.opspilot.application.capability.WindowResolver;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySnapshotV1.SnapshotCriterion;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationRepository.RecoveryVerificationRecord;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 采集一个恢复样本槽位（08 TASK-078、06 §113、04 §80、07 §69）：
 * <ol>
 *   <li>准入短事务：第一条语句即锁 Incident（须 VERIFYING），之后重读 Verification 仍为 RUNNING 且未到冻结的 deadline；运行时按 Incident 所属系统复核
 *       该能力在目标资源上仍可用（绑定、唯一 Provider，06 §107：不能凭旧快照越过已撤销的访问）；以快照中的强类型参数登记
 *       verificationId＋criterionKey＋sampleIndex 的 RUNNING Invocation。槽位已被占用则不登记——同一 sampleIndex 不重试。
 *       不经过调查 Duplicate Guard，不扣调查预算，不调用 AI。
 *   <li>提交后在事务外经与调查相同的 Invoker 调用真实 Provider，调用期限为能力超时与冻结 deadline 中较早者（B28-R1），再以既有
 *       结果事务落账（成功写 Observation，失败只记错误）。
 * </ol>
 * 等待（interval）由调用方在事务外完成；本类不 sleep。
 */
@Service
public class RecoverySampler {

    private final IncidentRepository incidents;
    private final RecoveryVerificationRepository verifications;
    private final ManagedResourceRepository resources;
    private final CapabilityAccess access;
    private final CapabilityInvocationRepository invocations;
    private final CapabilityExecutionService execution;
    private final CanonicalJsonWriter canonicalJson;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RecoverySampler(
            IncidentRepository incidents,
            RecoveryVerificationRepository verifications,
            ManagedResourceRepository resources,
            CapabilityAccess access,
            CapabilityInvocationRepository invocations,
            CapabilityExecutionService execution,
            CanonicalJsonWriter canonicalJson,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.verifications = verifications;
        this.resources = resources;
        this.access = access;
        this.invocations = invocations;
        this.execution = execution;
        this.canonicalJson = canonicalJson;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 一次采样尝试的结果。 */
    public sealed interface SampleAttempt {}

    /** 槽位已登记并执行；调用的成功或失败已由结果事务落账。 */
    public record Sampled(long invocationId, CapabilityExecutionResult result) implements SampleAttempt {}

    /** 运行时能力不可用，没有登记调用。 */
    public record NotAdmitted(ErrorCode code) implements SampleAttempt {}

    /** Verification 已不在 RUNNING、Incident 不在 VERIFYING 或已到 deadline；不再采样。 */
    public record Stopped(String reason) implements SampleAttempt {

        public static final String NOT_RUNNING = "NOT_RUNNING";
        public static final String DEADLINE_REACHED = "DEADLINE_REACHED";
    }

    /** 该槽位已有调用（另一执行者）；不在同一 sampleIndex 重试。 */
    public record SlotTaken() implements SampleAttempt {}

    public SampleAttempt sample(long verificationId, SnapshotCriterion criterion, int sampleIndex) {
        Objects.requireNonNull(criterion, "criterion");
        // 所属 Incident 创建后不变：在事务外读出，事务的第一条语句才能是 Incident 行锁
        long incidentId = verifications
                .findById(verificationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown recovery verification: " + verificationId))
                .incidentId();
        Admission admission = transaction.execute(status -> admit(verificationId, incidentId, criterion, sampleIndex));
        return switch (Objects.requireNonNull(admission)) {
            case Admission.Rejected rejected -> rejected.attempt();
            case Admission.Admitted admitted ->
                new Sampled(admitted.invocation().invocationId(), execution.executeAdmitted(admitted.invocation()));
        };
    }

    private sealed interface Admission {

        record Admitted(AdmittedInvocation invocation) implements Admission {}

        record Rejected(SampleAttempt attempt) implements Admission {}
    }

    /**
     * 准入事务。第一条语句必须是 Incident 行锁：MySQL REPEATABLE READ 在第一次普通读取时建立一致性视图，锁之前的普通读取会让之后的
     * 重读与能力复核看不到锁等待期间已提交的撤权（B28-R1）。
     */
    private Admission admit(long verificationId, long incidentId, SnapshotCriterion criterion, int sampleIndex) {
        Incident incident = incidents
                .findByIdForUpdate(incidentId)
                .orElseThrow(() -> new IllegalStateException("Verification without incident: " + verificationId));
        RecoveryVerificationRecord current =
                verifications.findById(verificationId).orElseThrow();
        if (current.status() != RecoveryVerificationStatus.RUNNING || incident.status() != IncidentStatus.VERIFYING) {
            return new Admission.Rejected(new Stopped(Stopped.NOT_RUNNING));
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!now.isBefore(current.deadlineAt())) {
            return new Admission.Rejected(new Stopped(Stopped.DEADLINE_REACHED));
        }
        RecoveryCriterionV1 definition = criterion.criterion();
        CapabilityAccess.Decision decision = access.evaluate(
                incident.managedSystemId(),
                resources.findById(criterion.targetResourceId()),
                definition.capabilityKey().key());
        if (decision instanceof CapabilityAccess.Denied denied) {
            return new Admission.Rejected(new NotAdmitted(denied.code()));
        }
        CapabilityAccess.Allowed allowed = (CapabilityAccess.Allowed) decision;
        WindowResolver.Resolution resolution = window(definition.arguments(), incident.startedAt(), now);
        if (resolution instanceof WindowResolver.Invalid invalid) {
            return new Admission.Rejected(new NotAdmitted(invalid.code()));
        }
        ResolvedWindow window = resolution instanceof WindowResolver.Resolved resolved ? resolved.window() : null;
        Optional<Long> invocationId = invocations.insertRunningRecoverySample(new NewRecoverySampleInvocation(
                incident.id(),
                verificationId,
                definition.criterionKey(),
                sampleIndex,
                definition.capabilityKey().key(),
                allowed.resource().id(),
                allowed.definition().requestSchema(),
                canonicalJson.write(definition.arguments()),
                now,
                Correlation.currentId()));
        if (invocationId.isEmpty()) {
            return new Admission.Rejected(new SlotTaken());
        }
        return new Admission.Admitted(new AdmittedInvocation(
                invocationId.get(),
                incident.id(),
                null,
                null,
                allowed.resource(),
                allowed.definition(),
                allowed.provider(),
                definition.arguments(),
                window,
                now,
                current.deadlineAt()));
    }

    /** 与调查准入相同的窗口解析（06 §42）；没有 windowKey 的能力为空。 */
    private static WindowResolver.Resolution window(
            CapabilityArguments arguments, Instant incidentStartedAt, Instant now) {
        return switch (arguments) {
            case MetricsQueryArgumentsV1 metrics ->
                WindowResolver.resolve(metrics.windowKey(), metrics.comparePreviousWindow(), incidentStartedAt, now);
            case LogsSearchArgumentsV1 logs -> WindowResolver.resolve(logs.windowKey(), false, incidentStartedAt, now);
            default -> null;
        };
    }
}
