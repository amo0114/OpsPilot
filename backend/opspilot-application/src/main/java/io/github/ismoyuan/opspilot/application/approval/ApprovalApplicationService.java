package io.github.ismoyuan.opspilot.application.approval;

import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySelector;
import io.github.ismoyuan.opspilot.application.remediation.RemediationContextQuery;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTransition;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalRequest;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.remediation.RemediationPlanStatus;
import io.github.ismoyuan.opspilot.domain.timeline.ApprovalDecidedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Approval 查询与决定（08 TASK-066、05 §37～§43、04 §44、§78）。每个决定是一个短事务，锁序 Incident → Approval。拒绝与撤回：
 * Approval PENDING → REJECTED / CANCELLED（记决定人、时间与说明，版本加一）、方案 ACTIVE → CANCELLED（本批决定：已决定的 Action
 * 不能再审批，不留下看似可执行的方案）、Incident AWAITING_APPROVAL → DIAGNOSED 并写时间线。同一操作者重复提交相同决定与原说明
 * 返回原结果，这一判定先于版本校验（05 §43）；其他已决定情况 APPROVAL_ALREADY_DECIDED。
 *
 * <p>批准：同事务按 05 §38、04 §78 复核 PENDING、版本、AWAITING_APPROVAL、Plan ACTIVE 且基于最新 Diagnosis、目标资源与
 * service.restart 绑定仍可执行，并选择目标资源唯一合法的 ACTIVE RecoveryPolicy（{@link RecoveryPolicySelector}：没有、多条或按
 * 此刻配置不合法都在任何写入与外部副作用前拒绝，08 TASK-067）。成功路径——冻结快照、PENDING → APPROVED、创建 ActionExecution、
 * → EXECUTING——属 TASK-069；在此之前全部复核通过也不写入任何数据，以 REMEDIATION_ACTION_NOT_EXECUTABLE
 * （reason=EXECUTION_NOT_AVAILABLE）拒绝，不以半套 APPROVED 冒充成功（08 TASK-066 分阶段边界）。
 *
 * <p>一致性读：Approval 所属 Incident 创建后不变，在事务之外查出；事务内的第一条语句就是 Incident → Approval 行锁，之后的
 * Diagnosis、资源、绑定与策略复核都读取取锁之后的数据，不使用等锁之前建立的 REPEATABLE READ 快照（B21-R1）。
 */
@Service
public class ApprovalApplicationService {

    private final ApprovalRepository approvals;
    private final IncidentRepository incidents;
    private final RemediationContextQuery diagnoses;
    private final ManagedResourceRepository resources;
    private final CapabilityAccess access;
    private final RecoveryPolicySelector recoveryPolicies;
    private final TimelineRepository timeline;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ApprovalApplicationService(
            ApprovalRepository approvals,
            IncidentRepository incidents,
            RemediationContextQuery diagnoses,
            ManagedResourceRepository resources,
            CapabilityAccess access,
            RecoveryPolicySelector recoveryPolicies,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.approvals = approvals;
        this.incidents = incidents;
        this.diagnoses = diagnoses;
        this.resources = resources;
        this.access = access;
        this.recoveryPolicies = recoveryPolicies;
        this.timeline = timeline;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ApprovalRepository.ApprovalView getApproval(long approvalId) {
        return approvals.findView(approvalId).orElseThrow(() -> notFound(approvalId));
    }

    public ApprovalDecisionResult reject(ApprovalDecisionCommand command) {
        return decide(command, ApprovalStatus.REJECTED, IncidentTrigger.REJECT_APPROVAL);
    }

    public ApprovalDecisionResult cancel(ApprovalDecisionCommand command) {
        return decide(command, ApprovalStatus.CANCELLED, IncidentTrigger.CANCEL_APPROVAL);
    }

    /**
     * @throws ApplicationException 复核失败时的状态/版本/方案/动作/恢复策略错误；复核全部通过时 REMEDIATION_ACTION_NOT_EXECUTABLE
     *     （reason=EXECUTION_NOT_AVAILABLE，TASK-069 前的过渡，见类说明）
     */
    public ApprovalDecisionResult approve(ApprovalDecisionCommand command) {
        comment(command.comment()); // 与拒绝/撤回同样校验说明的格式
        long incidentId = incidentOf(command.approvalId());
        return transaction.execute(status -> {
            Locked locked = lock(incidentId, command.approvalId());
            ApprovalRequest approval = locked.approved().approval();
            if (approval.status().isDecided()) {
                throw approval.alreadyDecided();
            }
            checkApprovalVersion(approval, command);
            locked.incident().transitionFor(IncidentTrigger.START_EXECUTION, command.expectedIncidentVersion());
            ApprovalRepository.LockedApproval target = locked.approved();
            long latest = diagnoses
                    .findLatestDiagnosis(locked.incident().id())
                    .map(RemediationContextQuery.LatestDiagnosis::id)
                    .orElse(-1L);
            if (target.planStatus() != RemediationPlanStatus.ACTIVE || target.diagnosisId() != latest) {
                throw new ApplicationException(
                        ErrorCode.REMEDIATION_PLAN_SUPERSEDED,
                        "Plan is no longer active or not based on the latest diagnosis",
                        Map.of(
                                "approvalId",
                                approval.id(),
                                "planStatus",
                                target.planStatus().name()));
            }
            if (!(access.evaluateChange(
                            locked.incident().managedSystemId(),
                            resources.findById(target.targetResourceId()),
                            target.capabilityKey())
                    instanceof CapabilityAccess.Allowed allowed)) {
                throw new ApplicationException(
                        ErrorCode.REMEDIATION_ACTION_NOT_EXECUTABLE,
                        "Action is no longer executable",
                        Map.of("approvalId", approval.id(), "capabilityKey", target.capabilityKey()));
            }
            recoveryPolicies.select(allowed.resource());
            // TASK-069：以所选策略冻结快照、PENDING → APPROVED、创建 ActionExecution、→ EXECUTING；接通前不写入
            throw new ApplicationException(
                    ErrorCode.REMEDIATION_ACTION_NOT_EXECUTABLE,
                    "Execution is not available yet",
                    Map.of("approvalId", approval.id(), "reason", "EXECUTION_NOT_AVAILABLE"));
        });
    }

    private ApprovalDecisionResult decide(
            ApprovalDecisionCommand command, ApprovalStatus target, IncidentTrigger trigger) {
        String comment = comment(command.comment());
        long incidentId = incidentOf(command.approvalId());
        return transaction.execute(status -> {
            Locked locked = lock(incidentId, command.approvalId());
            Incident incident = locked.incident();
            ApprovalRequest approval = locked.approved().approval();
            if (approval.isSameDecision(target, command.actor(), comment)) {
                return result(approval, incident);
            }
            if (approval.status().isDecided()) {
                throw approval.alreadyDecided();
            }
            checkApprovalVersion(approval, command);
            IncidentTransition transition = incident.transitionFor(trigger, command.expectedIncidentVersion());
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            ApprovalRequest decided = approval.decide(target, command.actor(), comment, now);
            approvals.saveDecision(approval, decided);
            approvals.cancelActivePlan(locked.approved().planId(), now);
            Incident diagnosed = incidents.apply(transition, now);
            boolean rejected = target == ApprovalStatus.REJECTED;
            timeline.append(new NewTimelineEvent(
                    incident.id(),
                    rejected ? TimelineEventType.APPROVAL_REJECTED : TimelineEventType.APPROVAL_CANCELLED,
                    now,
                    TimelineActorType.USER,
                    command.actor(),
                    (rejected ? "拒绝处理方案" : "撤回处理方案") + (comment == null ? "" : "：" + comment),
                    new ApprovalDecidedPayloadV1(
                            incident.incidentKey().value(),
                            approval.id(),
                            approval.remediationActionId(),
                            target.name(),
                            comment),
                    Correlation.currentId()));
            return result(decided, diagnosed);
        });
    }

    private record Locked(Incident incident, ApprovalRepository.LockedApproval approved) {}

    /** Approval 所属 Incident 创建后不变；在事务之外读出，不在决定事务中建立等锁之前的一致性读快照。 */
    private long incidentOf(long approvalId) {
        return approvals.findIncidentId(approvalId).orElseThrow(() -> notFound(approvalId));
    }

    /** Incident → Approval 锁序；必须是决定事务中最先执行的语句。 */
    private Locked lock(long incidentId, long approvalId) {
        Incident incident = incidents
                .findByIdForUpdate(incidentId)
                .orElseThrow(() -> new IllegalStateException("Approval without incident: " + approvalId));
        ApprovalRepository.LockedApproval approval =
                approvals.findForUpdate(approvalId).orElseThrow(() -> notFound(approvalId));
        return new Locked(incident, approval);
    }

    private static void checkApprovalVersion(ApprovalRequest approval, ApprovalDecisionCommand command) {
        if (approval.version() != command.expectedApprovalVersion()) {
            throw new ApplicationException(
                    ErrorCode.APPROVAL_VERSION_CONFLICT,
                    "Approval version changed",
                    Map.of("approvalId", approval.id(), "version", approval.version()));
        }
    }

    private static ApprovalDecisionResult result(ApprovalRequest approval, Incident incident) {
        return new ApprovalDecisionResult(
                approval.id(),
                approval.status(),
                approval.version(),
                incident.incidentKey(),
                incident.status(),
                incident.version());
    }

    private static String comment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String text = comment.strip();
        if (text.codePointCount(0, text.length()) > ApprovalRequest.COMMENT_MAX) {
            throw new ApplicationException(
                    ErrorCode.REQUEST_VALIDATION_FAILED,
                    "Approval comment too long",
                    Map.of("field", "comment", "reason", "TOO_LONG"));
        }
        return text;
    }

    private static ApplicationException notFound(long approvalId) {
        return new ApplicationException(
                ErrorCode.RESOURCE_NOT_FOUND, "Approval not found", Map.of("approvalId", approvalId));
    }
}
