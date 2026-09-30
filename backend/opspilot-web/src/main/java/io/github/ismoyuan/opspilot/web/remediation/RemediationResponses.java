package io.github.ismoyuan.opspilot.web.remediation;

import io.github.ismoyuan.opspilot.application.approval.ApprovalDecisionResult;
import io.github.ismoyuan.opspilot.application.approval.ApprovalRepository;
import io.github.ismoyuan.opspilot.application.remediation.RequestRemediationResult;
import io.github.ismoyuan.opspilot.application.remediation.ValidatedRemediationProposal;
import io.github.ismoyuan.opspilot.domain.capability.RiskLevel;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.remediation.RemediationPlanStatus;
import io.github.ismoyuan.opspilot.web.response.ApiTimes;

/** 05 §32、§37 与审批决定的响应体。 */
final class RemediationResponses {

    private RemediationResponses() {}

    record TargetResource(String resourceKey, String name) {}

    /** 05 §32。 */
    record RequestRemediationResponse(
            String incidentKey, IncidentStatus status, long version, Plan plan, Action action, Approval approval) {

        record Plan(long planId, String title, String summary, RemediationPlanStatus status) {}

        record Action(
                long actionId,
                String capabilityKey,
                TargetResource targetResource,
                String summary,
                RiskLevel riskLevel,
                String expectedImpactSummary) {}

        record Approval(long approvalId, ApprovalStatus status) {}

        static RequestRemediationResponse of(RequestRemediationResult result) {
            ValidatedRemediationProposal proposal = result.proposal();
            return new RequestRemediationResponse(
                    result.incidentKey().value(),
                    result.status(),
                    result.version(),
                    new Plan(result.planId(), proposal.title(), proposal.summary(), RemediationPlanStatus.ACTIVE),
                    new Action(
                            result.actionId(),
                            proposal.capabilityKey(),
                            new TargetResource(
                                    proposal.target().resourceKey(),
                                    proposal.target().name()),
                            proposal.actionSummary(),
                            proposal.riskLevel(),
                            proposal.expectedImpactSummary()),
                    new Approval(result.approvalId(), ApprovalStatus.PENDING));
        }
    }

    /** 05 §37；决定字段在 PENDING 时为空。 */
    record ApprovalResponse(
            long approvalId,
            ApprovalStatus status,
            long version,
            String incidentKey,
            Action action,
            String requestedAt,
            String decidedBy,
            String decidedAt,
            String comment) {

        record Action(
                long actionId,
                String summary,
                String capabilityKey,
                TargetResource targetResource,
                RiskLevel riskLevel,
                String expectedImpactSummary) {}

        static ApprovalResponse of(ApprovalRepository.ApprovalView view) {
            return new ApprovalResponse(
                    view.approvalId(),
                    view.status(),
                    view.version(),
                    view.incidentKey(),
                    new Action(
                            view.actionId(),
                            view.actionSummary(),
                            view.capabilityKey(),
                            new TargetResource(view.resourceKey(), view.resourceName()),
                            view.riskLevel(),
                            view.expectedImpactSummary()),
                    ApiTimes.format(view.requestedAt()),
                    view.decidedBy(),
                    view.decidedAt() == null ? null : ApiTimes.format(view.decidedAt()),
                    view.comment());
        }
    }

    record ApprovalDecisionResponse(
            long approvalId,
            ApprovalStatus approvalStatus,
            long approvalVersion,
            String incidentKey,
            IncidentStatus incidentStatus,
            long incidentVersion) {

        static ApprovalDecisionResponse of(ApprovalDecisionResult result) {
            return new ApprovalDecisionResponse(
                    result.approvalId(),
                    result.approvalStatus(),
                    result.approvalVersion(),
                    result.incidentKey().value(),
                    result.incidentStatus(),
                    result.incidentVersion());
        }
    }
}
