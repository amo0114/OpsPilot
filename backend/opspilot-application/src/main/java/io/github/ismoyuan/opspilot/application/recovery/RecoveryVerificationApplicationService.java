package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.IncidentLocks;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTransition;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.RecoveryVerificationRequestedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 外部处理后的恢复验证入口（08 TASK-081、05 §34～§35、§91）。一个短事务，第一条语句即按编号锁 Incident：
 * <ol>
 *   <li>DIAGNOSED 且版本等于 expectedVersion（否则 INCIDENT_STATE_CONFLICT / INCIDENT_VERSION_CONFLICT）；
 *   <li>resourceKey 是本 Incident 所属系统内的资源（RESOURCE_NOT_IN_SYSTEM）；
 *   <li>没有 PENDING/RUNNING Verification（RECOVERY_VERIFICATION_ALREADY_RUNNING），旧结果与期限不被更新或刷新；
 *   <li>在本事务选择该资源唯一合法的 ACTIVE RecoveryPolicy 并冻结完整快照（RECOVERY_POLICY_NOT_FOUND / AMBIGUOUS，04 §52）；
 *   <li>创建 action_execution_id 为空的 PENDING Verification（不伪造 Execution，04 §54），DIAGNOSED → VERIFYING，写
 *       RECOVERY_VERIFICATION_REQUESTED，提交后派发。
 * </ol>
 * 任何拒绝都不写入。INCONCLUSIVE 回到 DIAGNOSED 后再次调用即新建下一个编号的 Verification（05 §35）。
 */
@Service
public class RecoveryVerificationApplicationService {

    /** 说明上限（与审批说明一致）。 */
    static final int NOTE_MAX = 500;

    private final IncidentRepository incidents;
    private final ManagedResourceRepository resources;
    private final RecoveryVerificationRepository verifications;
    private final RecoveryPolicySelector policies;
    private final RecoveryVerificationCreator creator;
    private final SchemaCodecRegistry codecs;
    private final TimelineRepository timeline;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RecoveryVerificationApplicationService(
            IncidentRepository incidents,
            ManagedResourceRepository resources,
            RecoveryVerificationRepository verifications,
            RecoveryPolicySelector policies,
            RecoveryVerificationCreator creator,
            SchemaCodecRegistry codecs,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.resources = resources;
        this.verifications = verifications;
        this.policies = policies;
        this.creator = creator;
        this.codecs = codecs;
        this.timeline = timeline;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @throws ApplicationException 见类说明；均不写入
     */
    public VerifyRecoveryResult verifyRecovery(VerifyRecoveryCommand command) {
        String note = note(command.note());
        return transaction.execute(status -> {
            Incident incident = IncidentLocks.lockByKey(incidents, command.incidentKey());
            IncidentTransition transition =
                    incident.transitionFor(IncidentTrigger.VERIFY_RECOVERY, command.expectedVersion());
            ManagedResource resource = resources
                    .findBySystemIdAndResourceKey(incident.managedSystemId(), command.resourceKey())
                    .orElseThrow(() -> new ApplicationException(
                            ErrorCode.RESOURCE_NOT_IN_SYSTEM,
                            "Resource not found in the incident's system",
                            Map.of("resourceKey", String.valueOf(command.resourceKey()))));
            if (verifications.existsActive(incident.id())) {
                throw new ApplicationException(
                        ErrorCode.RECOVERY_VERIFICATION_ALREADY_RUNNING,
                        "A recovery verification is already pending or running",
                        Map.of("incidentKey", incident.incidentKey().value()));
            }
            RecoveryPolicySelector.SelectedRecoveryPolicy policy = policies.select(resource);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            RecoveryVerificationCreator.Created created = creator.createPending(
                    incident,
                    null,
                    policy.policy().id(),
                    policy.policy().versionNo(),
                    codecs.encode(
                            RecoveryPolicySnapshotV1.SCHEMA_NAME,
                            RecoveryPolicySnapshotV1.SCHEMA_VERSION,
                            RecoveryPolicySnapshotV1.of(policy)),
                    now);
            Incident verifying = incidents.apply(transition, now);
            timeline.append(new NewTimelineEvent(
                    incident.id(),
                    TimelineEventType.RECOVERY_VERIFICATION_REQUESTED,
                    now,
                    TimelineActorType.USER,
                    command.actor(),
                    "请求恢复验证：" + resource.resourceKey() + (note == null ? "" : "（" + note + "）"),
                    new RecoveryVerificationRequestedPayloadV1(
                            incident.incidentKey().value(),
                            created.verificationId(),
                            created.verificationNo(),
                            resource.resourceKey(),
                            policy.policy().id(),
                            policy.policy().versionNo(),
                            note),
                    Correlation.currentId()));
            return new VerifyRecoveryResult(
                    verifying.incidentKey(),
                    verifying.status(),
                    verifying.version(),
                    created.verificationNo(),
                    RecoveryVerificationStatus.PENDING);
        });
    }

    private static String note(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String text = note.strip();
        if (text.codePointCount(0, text.length()) > NOTE_MAX) {
            throw new ApplicationException(
                    ErrorCode.REQUEST_VALIDATION_FAILED,
                    "Note too long",
                    Map.of("field", "note", "reason", "TOO_LONG"));
        }
        return text;
    }
}
