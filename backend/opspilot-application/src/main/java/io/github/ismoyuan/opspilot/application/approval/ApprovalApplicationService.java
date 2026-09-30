package io.github.ismoyuan.opspilot.application.approval;

import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
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
 * <p>批准：同事务按 05 §38 复核 PENDING、版本、AWAITING_APPROVAL、Plan ACTIVE 且基于最新 Diagnosis、目标资源与 service.restart 绑定
 * 仍可执行；随后必须选择唯一 ACTIVE RecoveryPolicy 并冻结快照、创建 ActionExecution——RecoveryPolicy 与 Execution 由 TASK-067～069、
 * 074～076 建立，在此之前不存在任何可用策略，因此如实以 RECOVERY_POLICY_NOT_FOUND 拒绝，Approval 保持 PENDING、Incident 保持
 * AWAITING_APPROVAL，不以半套 APPROVED 冒充成功（08 TASK-066 分阶段边界）。
 */
@Service
public class ApprovalApplicationService {

    private final ApprovalRepository approvals;
    private final IncidentRepository incidents;
    private final RemediationContextQuery diagnoses;
    private final ManagedResourceRepository resources;
    private final CapabilityAccess access;
    private final TimelineRepository timeline;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ApprovalApplicationService(
            ApprovalRepository approvals,
            IncidentRepository incidents,
            RemediationContextQuery diagnoses,
            ManagedResourceRepository resources,
            CapabilityAccess access,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.approvals = approvals;
        this.incidents = incidents;
        this.diagnoses = diagnoses;
        this.resources = resources;
        this.access = access;
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
     * @throws ApplicationException 复核失败时的状态/版本/方案/动作错误；复核全部通过时 RECOVERY_POLICY_NOT_FOUND（见类说明）
     */
    public ApprovalDecisionResult approve(ApprovalDecisionCommand command) {
        comment(command.comment()); // 与拒绝/撤回同样校验说明的格式
        return transaction.execute(status -> {
            Locked locked = lock(command.approvalId());
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
                    instanceof CapabilityAccess.Allowed)) {
                throw new ApplicationException(
                        ErrorCode.REMEDIATION_ACTION_NOT_EXECUTABLE,
                        "Action is no longer executable",
                        Map.of("approvalId", approval.id(), "capabilityKey", target.capabilityKey()));
            }
            // TASK-069：此处选择唯一 ACTIVE RecoveryPolicy、冻结快照、PENDING → APPROVED、创建 ActionExecution、→ EXECUTING
            throw new ApplicationException(
                    ErrorCode.RECOVERY_POLICY_NOT_FOUND,
                    "No recovery policy is available for the target",
                    Map.of("approvalId", approval.id(), "targetResourceId", target.targetResourceId()));
        });
    }

    private ApprovalDecisionResult decide(
            ApprovalDecisionCommand command, ApprovalStatus target, IncidentTrigger trigger) {
        String comment = comment(command.comment());
        return transaction.execute(status -> {
            Locked locked = lock(command.approvalId());
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

    /** Incident → Approval 锁序；Approval 所属 Incident 创建后不变，先无锁读出。 */
    private Locked lock(long approvalId) {
        long incidentId = approvals.findIncidentId(approvalId).orElseThrow(() -> notFound(approvalId));
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
