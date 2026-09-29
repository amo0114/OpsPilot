package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.investigation.context.ContextHead;
import io.github.ismoyuan.opspilot.application.investigation.context.InvestigationContextQuery;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationContextRows.DiagnosisRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationContextRows.HeadRow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisInvestigationContextQuery implements InvestigationContextQuery {

    private final InvestigationContextMapper mapper;

    MyBatisInvestigationContextQuery(InvestigationContextMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ContextHead> findHead(long incidentId) {
        HeadRow row = mapper.selectHead(incidentId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new ContextHead(
                row.incidentId(),
                row.incidentKey(),
                row.title(),
                row.impactSummary(),
                instant(row.startedAt()),
                IncidentStatus.valueOf(row.status()),
                row.investigationId(),
                row.currentRunNo(),
                instant(row.currentRunStartedAt()),
                row.currentRunCapabilityCount(),
                row.maxCapabilityCalls(),
                row.maxDurationSeconds()));
    }

    @Override
    public List<InvestigationStepRequest.AffectedResource> findAffectedResources(long incidentId) {
        return mapper.selectAffectedResources(incidentId).stream()
                .map(r -> new InvestigationStepRequest.AffectedResource(
                        r.resourceId(), r.resourceKey(), ResourceType.valueOf(r.resourceType())))
                .toList();
    }

    @Override
    public List<InvestigationStepRequest.Hypothesis> findHypotheses(long investigationId) {
        return mapper.selectHypotheses(investigationId).stream()
                .map(h -> new InvestigationStepRequest.Hypothesis(
                        h.id(), h.title(), h.description(), HypothesisStatus.valueOf(h.status())))
                .toList();
    }

    @Override
    public List<InvestigationStepRequest.Evidence> findContextEvidence(long investigationId, Instant runStartedAt) {
        return mapper.selectContextEvidence(investigationId, utc(runStartedAt)).stream()
                .map(e -> new InvestigationStepRequest.Evidence(
                        e.id(),
                        e.observationId(),
                        e.hypothesisId(),
                        EvidenceRelation.valueOf(e.relation()),
                        e.reason()))
                .toList();
    }

    @Override
    public List<InvestigationStepRequest.Observation> findContextObservations(
            long investigationId,
            int runNo,
            Collection<Long> referencedObservationIds,
            int maxLogPatternsPerInvocation) {
        return mapper
                .selectContextObservations(
                        investigationId, runNo, referencedObservationIds, maxLogPatternsPerInvocation)
                .stream()
                .map(o -> new InvestigationStepRequest.Observation(
                        o.id(),
                        o.runNo(),
                        o.resourceKey(),
                        ObservationKind.valueOf(o.kind()),
                        o.summary(),
                        instant(o.observedAt())))
                .toList();
    }

    @Override
    public Optional<InvestigationStepRequest.CurrentDiagnosis> findLatestDiagnosis(long investigationId) {
        DiagnosisRow row = mapper.selectLatestDiagnosis(investigationId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new InvestigationStepRequest.CurrentDiagnosis(
                row.version(),
                row.runNo(),
                DiagnosisConclusionType.valueOf(row.conclusionType()),
                row.primaryHypothesisId(),
                row.summary(),
                mapper.selectDiagnosisEvidenceIds(row.id())));
    }

    @Override
    public List<InvestigationStepRequest.TimelineEntry> findRecentTimeline(
            long incidentId, Set<String> eventTypes, int limit) {
        return mapper.selectRecentTimeline(incidentId, eventTypes, limit).stream()
                .map(t ->
                        new InvestigationStepRequest.TimelineEntry(t.eventType(), instant(t.occurredAt()), t.summary()))
                .toList();
    }

    private static Instant instant(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
