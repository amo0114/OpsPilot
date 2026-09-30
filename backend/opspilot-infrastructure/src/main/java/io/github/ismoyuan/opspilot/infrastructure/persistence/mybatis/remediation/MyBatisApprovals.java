package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.remediation;

import io.github.ismoyuan.opspilot.application.approval.ApprovalRepository;
import io.github.ismoyuan.opspilot.application.remediation.RemediationRepository;
import io.github.ismoyuan.opspilot.application.remediation.ValidatedRemediationProposal;
import io.github.ismoyuan.opspilot.domain.capability.RiskLevel;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalRequest;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.remediation.RemediationPlanStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisApprovals implements RemediationRepository, ApprovalRepository {

    private final ApprovalMapper mapper;

    MyBatisApprovals(ApprovalMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public CreatedRemediation insertProposal(long incidentId, ValidatedRemediationProposal proposal, Instant at) {
        LocalDateTime now = utc(at);
        GeneratedKey plan = new GeneratedKey();
        mapper.insertPlan(plan, incidentId, proposal.diagnosisId(), proposal.title(), proposal.summary(), now);
        GeneratedKey action = new GeneratedKey();
        mapper.insertAction(
                action,
                plan.getId(),
                proposal.capabilityKey(),
                proposal.target().id(),
                proposal.parameterSchema().name(),
                proposal.parameterSchema().version(),
                proposal.parameterPayload(),
                proposal.actionSummary(),
                proposal.expectedImpactSummary(),
                proposal.riskLevel().name(),
                proposal.requiresApproval(),
                now);
        GeneratedKey approval = new GeneratedKey();
        mapper.insertApproval(approval, action.getId(), now);
        return new CreatedRemediation(plan.getId(), action.getId(), approval.getId());
    }

    @Override
    public Optional<Long> findIncidentId(long approvalId) {
        return Optional.ofNullable(mapper.selectIncidentId(approvalId));
    }

    @Override
    public Optional<LockedApproval> findForUpdate(long approvalId) {
        return Optional.ofNullable(mapper.selectForUpdate(approvalId))
                .map(row -> new LockedApproval(
                        new ApprovalRequest(
                                row.id(),
                                row.actionId(),
                                ApprovalStatus.valueOf(row.status()),
                                instant(row.requestedAt()),
                                row.decidedBy(),
                                instant(row.decidedAt()),
                                row.comment(),
                                row.lockVersion()),
                        row.planId(),
                        RemediationPlanStatus.valueOf(row.planStatus()),
                        row.diagnosisId(),
                        row.capabilityKey(),
                        row.targetResourceId()));
    }

    @Override
    public void saveDecision(ApprovalRequest before, ApprovalRequest after) {
        int updated = mapper.updateDecision(
                before.id(),
                after.status().name(),
                after.decidedBy(),
                utc(after.decidedAt()),
                after.comment(),
                before.version());
        if (updated != 1) {
            throw new IllegalStateException("Approval changed under lock: " + before.id());
        }
    }

    @Override
    public void cancelActivePlan(long planId, Instant at) {
        mapper.cancelActivePlan(planId, utc(at));
    }

    @Override
    public Optional<ApprovalView> findView(long approvalId) {
        return Optional.ofNullable(mapper.selectView(approvalId))
                .map(row -> new ApprovalView(
                        row.id(),
                        ApprovalStatus.valueOf(row.status()),
                        row.lockVersion(),
                        row.incidentKey(),
                        row.actionId(),
                        row.actionSummary(),
                        row.capabilityKey(),
                        row.resourceKey(),
                        row.resourceName(),
                        RiskLevel.valueOf(row.riskLevel()),
                        row.expectedImpactSummary(),
                        instant(row.requestedAt()),
                        row.decidedBy(),
                        instant(row.decidedAt()),
                        row.comment()));
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
