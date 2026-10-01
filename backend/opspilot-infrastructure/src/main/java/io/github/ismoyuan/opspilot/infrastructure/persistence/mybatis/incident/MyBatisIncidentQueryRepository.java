package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import io.github.ismoyuan.opspilot.application.incident.query.CurrentAssessmentView;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentFilter;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentQueryRepository;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentSnapshot;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentSummaryView;
import io.github.ismoyuan.opspilot.application.incident.query.RemediationView;
import io.github.ismoyuan.opspilot.domain.capability.RiskLevel;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.remediation.RemediationPlanStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisIncidentQueryRepository implements IncidentQueryRepository {

    private final IncidentQueryMapper mapper;

    MyBatisIncidentQueryRepository(IncidentQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long countIncidents(IncidentFilter filter) {
        return mapper.countIncidents(filter.systemKey(), statusName(filter));
    }

    @Override
    public List<IncidentSummaryView> findIncidents(IncidentFilter filter, int offset, int limit) {
        return mapper.selectIncidents(filter.systemKey(), statusName(filter), offset, limit).stream()
                .map(row -> new IncidentSummaryView(
                        row.incidentKey(),
                        row.title(),
                        row.systemName(),
                        IncidentStatus.valueOf(row.status()),
                        row.impactSummary(),
                        instant(row.detectedAt()),
                        instant(row.updatedAt())))
                .toList();
    }

    @Override
    public Optional<IncidentSnapshot> findSnapshot(String incidentKey) {
        return Optional.ofNullable(mapper.selectDetail(incidentKey))
                .map(row -> new IncidentSnapshot(
                        row.id(),
                        row.managedSystemId(),
                        row.incidentKey(),
                        row.title(),
                        row.description(),
                        row.systemKey(),
                        row.systemName(),
                        IncidentStatus.valueOf(row.status()),
                        row.lockVersion(),
                        row.impactSummary(),
                        instant(row.startedAt()),
                        instant(row.detectedAt()),
                        instant(row.resolvedAt()),
                        row.runNo(),
                        row.stopRequestedAt() != null,
                        row.lastTimelineEventId(),
                        mapper.selectAffectedResources(row.id())));
    }

    @Override
    public Optional<CurrentAssessmentView> findCurrentAssessment(long incidentId) {
        return Optional.ofNullable(mapper.selectLatestDiagnosis(incidentId))
                .map(row -> new CurrentAssessmentView(
                        row.versionNo(),
                        row.runNo(),
                        DiagnosisConclusionType.valueOf(row.conclusionType()),
                        row.summary(),
                        mapper.selectSupportingSummaries(row.id()).stream()
                                .distinct()
                                .toList(),
                        row.terminationReason() == null ? null : TerminationReason.valueOf(row.terminationReason()),
                        instant(row.createdAt())));
    }

    @Override
    public Optional<RemediationView> findLatestRemediation(long incidentId) {
        return Optional.ofNullable(mapper.selectLatestRemediation(incidentId))
                .map(row -> new RemediationView(
                        new RemediationView.Plan(
                                row.planId(),
                                row.planTitle(),
                                row.planSummary(),
                                RemediationPlanStatus.valueOf(row.planStatus()),
                                instant(row.planCreatedAt())),
                        new RemediationView.Action(
                                row.actionId(),
                                row.capabilityKey(),
                                row.targetResourceKey(),
                                row.targetResourceName(),
                                row.actionSummary(),
                                RiskLevel.valueOf(row.riskLevel()),
                                row.expectedImpactSummary(),
                                row.requiresApproval()),
                        row.approvalId() == null
                                ? null
                                : new RemediationView.Approval(
                                        row.approvalId(),
                                        ApprovalStatus.valueOf(row.approvalStatus()),
                                        row.approvalVersion(),
                                        instant(row.requestedAt()),
                                        instant(row.decidedAt())),
                        row.executionId() == null
                                ? null
                                : new RemediationView.Execution(
                                        row.executionId(),
                                        ActionExecutionStatus.valueOf(row.executionStatus()),
                                        instant(row.executionStartedAt()),
                                        instant(row.executionFinishedAt()),
                                        row.executionErrorCode())));
    }

    private static String statusName(IncidentFilter filter) {
        return filter.status() == null ? null : filter.status().name();
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
