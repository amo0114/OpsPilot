package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import io.github.ismoyuan.opspilot.application.investigation.query.DiagnosisDetailView;
import io.github.ismoyuan.opspilot.application.investigation.query.DiagnosisSummaryView;
import io.github.ismoyuan.opspilot.application.investigation.query.EvidenceView;
import io.github.ismoyuan.opspilot.application.investigation.query.HypothesisView;
import io.github.ismoyuan.opspilot.application.investigation.query.InvestigationFacts;
import io.github.ismoyuan.opspilot.application.investigation.query.InvestigationQueryRepository;
import io.github.ismoyuan.opspilot.application.investigation.query.InvestigationScope;
import io.github.ismoyuan.opspilot.application.investigation.query.ObservationDetailView;
import io.github.ismoyuan.opspilot.application.investigation.query.ObservationFilter;
import io.github.ismoyuan.opspilot.application.investigation.query.ObservationSummaryView;
import io.github.ismoyuan.opspilot.application.investigation.query.ResourceRefView;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationQueryRows.DiagnosisRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationQueryRows.FactsRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationQueryRows.ObservationRow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisInvestigationQueryRepository implements InvestigationQueryRepository {

    private final InvestigationQueryMapper mapper;

    MyBatisInvestigationQueryRepository(InvestigationQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<InvestigationScope> findScope(String incidentKey) {
        return Optional.ofNullable(mapper.selectScope(incidentKey))
                .map(row -> new InvestigationScope(
                        row.incidentId(),
                        IncidentStatus.valueOf(row.status()),
                        instant(row.updatedAt()),
                        row.investigationId()));
    }

    @Override
    public InvestigationFacts findFacts(long investigationId) {
        FactsRow row = mapper.selectFacts(investigationId);
        return new InvestigationFacts(
                row.runNo(),
                instant(row.startedAt()),
                instant(row.currentRunStartedAt()),
                instant(row.lastActivityAt()),
                row.stopRequestedAt() != null,
                row.currentRunCapabilityCount(),
                row.maxCapabilityCalls(),
                row.maxDurationSeconds(),
                row.totalCapabilityCalls(),
                row.hypothesisCount(),
                row.observationCount(),
                row.evidenceCount(),
                row.diagnosisVersions(),
                instant(row.currentRunDiagnosedAt()));
    }

    @Override
    public List<HypothesisView> findHypotheses(long investigationId) {
        return mapper.selectHypotheses(investigationId);
    }

    @Override
    public long countObservations(long investigationId, ObservationFilter filter) {
        return mapper.countObservations(investigationId, filter.resourceKey(), kindName(filter));
    }

    @Override
    public List<ObservationSummaryView> findObservations(
            long investigationId, ObservationFilter filter, int offset, int limit) {
        return mapper
                .selectObservations(investigationId, filter.resourceKey(), kindName(filter), offset, limit)
                .stream()
                .map(row -> new ObservationSummaryView(
                        row.id(),
                        ObservationKind.valueOf(row.kind()),
                        new ResourceRefView(row.resourceKey(), row.resourceName()),
                        row.summary(),
                        instant(row.observedAt()),
                        row.capabilityInvocationId()))
                .toList();
    }

    @Override
    public Optional<ObservationDetailView> findObservation(long investigationId, long observationId) {
        return Optional.ofNullable(mapper.selectObservation(investigationId, observationId))
                .map(MyBatisInvestigationQueryRepository::toDetail);
    }

    @Override
    public List<EvidenceView> findEvidence(long investigationId) {
        return mapper.selectEvidence(investigationId);
    }

    @Override
    public List<DiagnosisSummaryView> findDiagnoses(long investigationId) {
        return mapper.selectDiagnoses(investigationId).stream()
                .map(row -> new DiagnosisSummaryView(
                        row.version(),
                        DiagnosisConclusionType.valueOf(row.conclusionType()),
                        row.summary(),
                        instant(row.createdAt())))
                .toList();
    }

    @Override
    public Optional<DiagnosisDetailView> findDiagnosis(long investigationId, int version) {
        DiagnosisRow row = mapper.selectDiagnosis(investigationId, version);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new DiagnosisDetailView(
                row.version(),
                row.runNo(),
                DiagnosisConclusionType.valueOf(row.conclusionType()),
                row.primaryHypothesisId(),
                row.primaryHypothesisTitle(),
                row.summary(),
                row.impactSummary(),
                row.terminationReason() == null ? null : TerminationReason.valueOf(row.terminationReason()),
                instant(row.createdAt()),
                mapper.selectDiagnosisEvidence(row.id())));
    }

    private static ObservationDetailView toDetail(ObservationRow row) {
        return new ObservationDetailView(
                row.id(),
                ObservationKind.valueOf(row.kind()),
                new ResourceRefView(row.resourceKey(), row.resourceName()),
                row.summary(),
                instant(row.observedAt()),
                row.schemaName(),
                row.schemaVersion(),
                row.payload(),
                instant(row.windowStart()),
                instant(row.windowEnd()),
                row.capabilityInvocationId(),
                row.capabilityKey(),
                row.runNo());
    }

    private static String kindName(ObservationFilter filter) {
        return filter.kind() == null ? null : filter.kind().name();
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
