package io.github.ismoyuan.opspilot.application.remediation;

import io.github.ismoyuan.opspilot.application.ai.AiDecisionPort;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftResponse;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.IncidentLocks;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTransition;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import io.github.ismoyuan.opspilot.domain.timeline.ApprovalRequestedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.RemediationProposedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 请求处理建议（08 TASK-065、05 §29～§32、04 §77）。严格三段：只读快照（{@link RemediationDraftContextBuilder}）→ 退出事务调用 AI →
 * 新的短事务按 Incident 行锁复核并创建。LLM 调用不在任何事务内；AI 失败（503/504/502）或复核失效时不写任何记录，Incident 保持
 * DIAGNOSED。复核：Incident 仍为 DIAGNOSED 且版本与期望一致（经唯一转换入口）、最新 Diagnosis 仍是请求时那一版、所选动作在请求时的
 * allowedActions 中（否则 AI_INTENT_NOT_ALLOWED）且在此刻重新计算的 allowedActions 中（资源、绑定或 Provider 已变化时
 * REMEDIATION_ACTION_NOT_EXECUTABLE）。通过后同事务插入 Plan / Action / PENDING Approval、DIAGNOSED → AWAITING_APPROVAL，并追加
 * REMEDIATION_PROPOSED 与 APPROVAL_REQUESTED。riskLevel 与 requiresApproval 来自 Registry，不来自 AI。
 */
@Service
public class RemediationApplicationService {

    private final RemediationDraftContextBuilder contexts;
    private final AiDecisionPort ai;
    private final RemediationProposalValidator validator;
    private final RemediationActions actions;
    private final RemediationContextQuery query;
    private final RemediationRepository remediations;
    private final IncidentRepository incidents;
    private final TimelineRepository timeline;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RemediationApplicationService(
            RemediationDraftContextBuilder contexts,
            AiDecisionPort ai,
            RemediationProposalValidator validator,
            RemediationActions actions,
            RemediationContextQuery query,
            RemediationRepository remediations,
            IncidentRepository incidents,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.contexts = contexts;
        this.ai = ai;
        this.validator = validator;
        this.actions = actions;
        this.query = query;
        this.remediations = remediations;
        this.incidents = incidents;
        this.timeline = timeline;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @throws ApplicationException 上下文阶段的拒绝（见 {@link RemediationDraftContextBuilder#build}）、AI_RUNTIME_UNAVAILABLE /
     *     AI_RUNTIME_TIMEOUT / AI_OUTPUT_INVALID、AI_INTENT_NOT_ALLOWED、REMEDIATION_ACTION_NOT_EXECUTABLE，或复核时的状态/版本冲突
     */
    public RequestRemediationResult requestRemediation(RequestRemediationCommand command) {
        RemediationDraftContext context = contexts.build(command.incidentKey(), command.expectedVersion());
        RemediationDraftResponse response = ai.draftRemediation(context.request());
        // 先按请求时的 allowedActions 判定 AI 是否越界；落账前再按此刻的状态复核
        ValidatedRemediationProposal requested =
                validator.validate(context.allowedActions(), context.diagnosisId(), response);
        return transaction.execute(status -> create(command, context, response, requested));
    }

    private RequestRemediationResult create(
            RequestRemediationCommand command,
            RemediationDraftContext context,
            RemediationDraftResponse response,
            ValidatedRemediationProposal requested) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Incident incident = IncidentLocks.lockByKey(incidents, command.incidentKey());
        IncidentTransition transition =
                incident.transitionFor(IncidentTrigger.REQUEST_APPROVAL, command.expectedVersion());
        var latest = query.findLatestDiagnosis(incident.id());
        if (latest.isEmpty() || latest.get().id() != context.diagnosisId()) {
            throw new ApplicationException(
                    ErrorCode.INCIDENT_VERSION_CONFLICT,
                    "Diagnosis changed while the remediation was drafted",
                    Map.of(
                            "incidentKey", incident.incidentKey().value(),
                            "currentStatus", incident.status().name(),
                            "version", incident.version()));
        }
        List<AllowedRemediationAction> allowedNow = actions.allowedActionsForDiagnosis(
                incident.managedSystemId(), incident.id(), query.findFrozenEvidence(context.diagnosisId()));
        if (allowedNow.stream()
                .noneMatch(a ->
                        a.matches(requested.capabilityKey(), requested.target().id()))) {
            throw new ApplicationException(
                    ErrorCode.REMEDIATION_ACTION_NOT_EXECUTABLE,
                    "Proposed action is no longer executable",
                    Map.of(
                            "capabilityKey", requested.capabilityKey(),
                            "targetResourceId", requested.target().id(),
                            "reason", "ACTION_NO_LONGER_ALLOWED"));
        }
        ValidatedRemediationProposal proposal = validator.validate(allowedNow, context.diagnosisId(), response);
        RemediationRepository.CreatedRemediation created = remediations.insertProposal(incident.id(), proposal, now);
        Incident awaiting = incidents.apply(transition, now);
        String key = incident.incidentKey().value();
        timeline.append(new NewTimelineEvent(
                incident.id(),
                TimelineEventType.REMEDIATION_PROPOSED,
                now,
                TimelineActorType.AI_RUNTIME,
                null,
                "建议处理方案：" + proposal.actionSummary(),
                new RemediationProposedPayloadV1(
                        key,
                        proposal.diagnosisId(),
                        created.planId(),
                        created.actionId(),
                        proposal.capabilityKey(),
                        proposal.target().resourceKey(),
                        proposal.riskLevel().name()),
                Correlation.currentId()));
        timeline.append(new NewTimelineEvent(
                incident.id(),
                TimelineEventType.APPROVAL_REQUESTED,
                now,
                TimelineActorType.USER,
                command.actor(),
                "等待审批：" + proposal.actionSummary(),
                new ApprovalRequestedPayloadV1(key, created.approvalId(), created.actionId()),
                Correlation.currentId()));
        return new RequestRemediationResult(
                awaiting.incidentKey(),
                awaiting.status(),
                awaiting.version(),
                created.planId(),
                created.actionId(),
                created.approvalId(),
                proposal);
    }
}
