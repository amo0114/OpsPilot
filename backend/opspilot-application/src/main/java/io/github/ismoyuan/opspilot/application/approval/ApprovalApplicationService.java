package io.github.ismoyuan.opspilot.application.approval;

import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRepository;
import io.github.ismoyuan.opspilot.application.execution.ExecutionSettings;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutionContextV1;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySelector;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySnapshotV1;
import io.github.ismoyuan.opspilot.application.remediation.RemediationContextQuery;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionIdentity;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTransition;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalRequest;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.remediation.RemediationPlanStatus;
import io.github.ismoyuan.opspilot.domain.timeline.ApprovalApprovedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.ApprovalDecidedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Approval 查询与决定（08 TASK-066、05 §37～§43、04 §44、§78）。每个决定是一个短事务，锁序 Incident → Approval。拒绝与撤回：
 * Approval PENDING → REJECTED / CANCELLED（记决定人、时间与说明，版本加一）、方案 ACTIVE → CANCELLED（本批决定：已决定的 Action
 * 不能再审批，不留下看似可执行的方案）、Incident AWAITING_APPROVAL → DIAGNOSED 并写时间线。同一操作者重复提交相同决定与原说明
 * 返回原结果，这一判定先于版本校验（05 §43）；其他已决定情况 APPROVAL_ALREADY_DECIDED。
 *
 * <p>批准（04 §78、05 §38～§39、08 TASK-069）：同一短事务复核 PENDING、版本、AWAITING_APPROVAL、Plan ACTIVE 且基于最新
 * Diagnosis、目标资源与 service.restart 绑定仍可执行，并选择目标资源唯一合法的 ACTIVE RecoveryPolicy（{@link RecoveryPolicySelector}：
 * 没有、多条或按此刻配置不合法都在任何写入前拒绝）；随后冻结恢复合同快照与受信执行上下文，Approval PENDING → APPROVED、插入
 * PENDING ActionExecution、Incident AWAITING_APPROVAL → EXECUTING、写 APPROVAL_APPROVED，一起提交；提交之后才派发 Execution，
 * 派发失败由 PENDING 补派发来源恢复。事务内不调用任何外部系统。同一操作者重复提交相同批准与说明返回原决定与既有 Execution，
 * 先于版本判定，不再创建或派发（05 §43）。
 *
 * <p>一致性读：Approval 所属 Incident 创建后不变，在事务之外查出；事务内的第一条语句就是 Incident → Approval 行锁，之后的
 * Diagnosis、资源、绑定与策略复核都读取取锁之后的数据，不使用等锁之前建立的 REPEATABLE READ 快照（B21-R1）。
 */
@Service
public class ApprovalApplicationService {

    /** service.restart 的执行器（06 §101～§109）。 */
    static final String EXECUTOR_KEY = "docker.service-restart";

    private static final Logger log = LoggerFactory.getLogger(ApprovalApplicationService.class);

    private final ApprovalRepository approvals;
    private final IncidentRepository incidents;
    private final RemediationContextQuery diagnoses;
    private final ManagedResourceRepository resources;
    private final CapabilityAccess access;
    private final RecoveryPolicySelector recoveryPolicies;
    private final ActionExecutionRepository executions;
    private final ExecutionSettings executionSettings;
    private final SchemaCodecRegistry codecs;
    private final WorkDispatcher dispatcher;
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
            ActionExecutionRepository executions,
            ExecutionSettings executionSettings,
            SchemaCodecRegistry codecs,
            WorkDispatcher dispatcher,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.approvals = approvals;
        this.incidents = incidents;
        this.diagnoses = diagnoses;
        this.resources = resources;
        this.access = access;
        this.recoveryPolicies = recoveryPolicies;
        this.executions = executions;
        this.executionSettings = executionSettings;
        this.codecs = codecs;
        this.dispatcher = dispatcher;
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
     * @throws ApplicationException 复核失败时的状态/版本/方案/动作/恢复策略错误，均不写入
     */
    public ApprovalDecisionResult approve(ApprovalDecisionCommand command) {
        String comment = comment(command.comment());
        long incidentId = incidentOf(command.approvalId());
        return transaction.execute(status -> {
            Locked locked = lock(incidentId, command.approvalId());
            Incident incident = locked.incident();
            ApprovalRequest approval = locked.approved().approval();
            if (approval.isSameDecision(ApprovalStatus.APPROVED, command.actor(), comment)) {
                ActionExecutionRepository.ExecutionRef existing = executions
                        .findByActionId(approval.remediationActionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Approved action without execution: " + approval.remediationActionId()));
                return result(approval, incident, existing, true);
            }
            if (approval.status().isDecided()) {
                throw approval.alreadyDecided();
            }
            checkApprovalVersion(approval, command);
            IncidentTransition transition =
                    incident.transitionFor(IncidentTrigger.START_EXECUTION, command.expectedIncidentVersion());
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
            RecoveryPolicySelector.SelectedRecoveryPolicy policy = recoveryPolicies.select(allowed.resource());

            // 复核全部通过：冻结快照与执行上下文后一起落账（04 §78）
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            String snapshot = codecs.encode(
                    RecoveryPolicySnapshotV1.SCHEMA_NAME,
                    RecoveryPolicySnapshotV1.SCHEMA_VERSION,
                    RecoveryPolicySnapshotV1.of(policy));
            String context = codecs.encode(
                    ServiceRestartExecutionContextV1.SCHEMA_NAME,
                    ServiceRestartExecutionContextV1.SCHEMA_VERSION,
                    ServiceRestartExecutionContextV1.pending(allowed.resource().id(), allowed.provider()));
            ApprovalRequest decided = approval.decide(ApprovalStatus.APPROVED, command.actor(), comment, now);
            approvals.saveDecision(approval, decided);
            long executionId = executions.insertPending(new ActionExecutionRepository.NewActionExecution(
                    approval.remediationActionId(),
                    approval.id(),
                    ActionExecutionIdentity.idempotencyKey(approval.remediationActionId()),
                    EXECUTOR_KEY,
                    policy.policy().id(),
                    policy.policy().versionNo(),
                    snapshot,
                    ServiceRestartExecutionContextV1.SCHEMA_NAME,
                    ServiceRestartExecutionContextV1.SCHEMA_VERSION,
                    context,
                    executionSettings.maxReconciliationAttempts(),
                    Correlation.currentId(),
                    now));
            Incident executing = incidents.apply(transition, now);
            timeline.append(new NewTimelineEvent(
                    incident.id(),
                    TimelineEventType.APPROVAL_APPROVED,
                    now,
                    TimelineActorType.USER,
                    command.actor(),
                    "批准处理方案" + (comment == null ? "" : "：" + comment),
                    new ApprovalApprovedPayloadV1(
                            incident.incidentKey().value(),
                            approval.id(),
                            approval.remediationActionId(),
                            executionId,
                            policy.policy().id(),
                            policy.policy().versionNo(),
                            comment),
                    Correlation.currentId()));
            dispatchAfterCommit(executionId);
            return result(
                    decided,
                    executing,
                    new ActionExecutionRepository.ExecutionRef(executionId, ActionExecutionStatus.PENDING),
                    false);
        });
    }

    /** 提交后派发；派发异常只记录，Execution 已持久化为 PENDING，由补派发来源重新唤醒（07 §51）。 */
    private void dispatchAfterCommit(long executionId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    dispatcher.dispatchActionExecution(executionId);
                } catch (RuntimeException ex) {
                    log.warn(
                            "Execution dispatch failed after commit: executionId={} exception={}",
                            executionId,
                            ex.getClass().getName());
                }
            }
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
                return result(approval, incident, null, true);
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
            return result(decided, diagnosed, null, false);
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

    private static ApprovalDecisionResult result(
            ApprovalRequest approval,
            Incident incident,
            ActionExecutionRepository.ExecutionRef execution,
            boolean replayed) {
        return new ApprovalDecisionResult(
                approval.id(),
                approval.status(),
                approval.version(),
                incident.incidentKey(),
                incident.status(),
                incident.version(),
                execution,
                replayed);
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
