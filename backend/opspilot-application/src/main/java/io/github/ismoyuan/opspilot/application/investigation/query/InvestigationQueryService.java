package io.github.ismoyuan.opspilot.application.investigation.query;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.IncidentLocks;
import io.github.ismoyuan.opspilot.application.query.PageResult;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调查技术详情读取（08 TASK-027、05 §49～§56），全部只读。尚未开始调查时：概览返回 RESOURCE_NOT_FOUND，
 * 各列表为空，具体 Diagnosis 为 DIAGNOSIS_NOT_FOUND。只读事务保证同一请求读到一致快照。
 */
@Service
public class InvestigationQueryService {

    private final InvestigationQueryRepository repository;
    private final Clock clock;

    public InvestigationQueryService(InvestigationQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InvestigationOverviewView getOverview(String incidentKey) {
        InvestigationScope scope = scope(incidentKey);
        if (scope.investigationId() == null) {
            throw new ApplicationException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Investigation not started",
                    Map.of("incidentKey", incidentKey, "resource", "investigation"));
        }
        InvestigationFacts facts = repository.findFacts(scope.investigationId());
        return new InvestigationOverviewView(
                facts.runNo(),
                facts.startedAt(),
                facts.currentRunStartedAt(),
                facts.lastActivityAt(),
                facts.stopRequested(),
                new InvestigationOverviewView.RunBudgetView(
                        facts.currentRunCapabilityCount(),
                        facts.maxCapabilityCalls(),
                        Math.max(facts.maxCapabilityCalls() - facts.currentRunCapabilityCount(), 0),
                        durationSeconds(scope, facts),
                        facts.maxDurationSeconds()),
                facts.totalCapabilityCalls(),
                facts.hypothesisCount(),
                facts.observationCount(),
                facts.evidenceCount(),
                facts.diagnosisVersions());
    }

    @Transactional(readOnly = true)
    public List<HypothesisView> listHypotheses(String incidentKey) {
        Long investigationId = scope(incidentKey).investigationId();
        return investigationId == null ? List.of() : repository.findHypotheses(investigationId);
    }

    /** 分页参数已由 web 边界校验。 */
    @Transactional(readOnly = true)
    public PageResult<ObservationSummaryView> listObservations(
            String incidentKey, ObservationFilter filter, int page, int size) {
        Long investigationId = scope(incidentKey).investigationId();
        if (investigationId == null) {
            return new PageResult<>(List.of(), page, size, 0);
        }
        return new PageResult<>(
                repository.findObservations(investigationId, filter, Math.multiplyExact(page, size), size),
                page,
                size,
                repository.countObservations(investigationId, filter));
    }

    @Transactional(readOnly = true)
    public ObservationDetailView getObservation(String incidentKey, long observationId) {
        Long investigationId = scope(incidentKey).investigationId();
        return (investigationId == null
                        ? Optional.<ObservationDetailView>empty()
                        : repository.findObservation(investigationId, observationId))
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Observation not found",
                        Map.of("incidentKey", incidentKey, "observationId", observationId)));
    }

    @Transactional(readOnly = true)
    public List<EvidenceView> listEvidence(String incidentKey) {
        Long investigationId = scope(incidentKey).investigationId();
        return investigationId == null ? List.of() : repository.findEvidence(investigationId);
    }

    @Transactional(readOnly = true)
    public List<DiagnosisSummaryView> listDiagnoses(String incidentKey) {
        Long investigationId = scope(incidentKey).investigationId();
        return investigationId == null ? List.of() : repository.findDiagnoses(investigationId);
    }

    @Transactional(readOnly = true)
    public DiagnosisDetailView getDiagnosis(String incidentKey, int version) {
        Long investigationId = scope(incidentKey).investigationId();
        return (investigationId == null
                        ? Optional.<DiagnosisDetailView>empty()
                        : repository.findDiagnosis(investigationId, version))
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.DIAGNOSIS_NOT_FOUND,
                        "Diagnosis version not found",
                        Map.of("incidentKey", incidentKey, "version", version)));
    }

    private InvestigationScope scope(String incidentKey) {
        return repository.findScope(incidentKey).orElseThrow(() -> IncidentLocks.notFound(incidentKey));
    }

    /** 本轮结束时刻：已收束取本轮 Diagnosis 时间；仍在调查取当前时间；其他结束（如取消）取 Incident 更新时间。 */
    private long durationSeconds(InvestigationScope scope, InvestigationFacts facts) {
        Instant end;
        if (facts.currentRunDiagnosedAt() != null) {
            end = facts.currentRunDiagnosedAt();
        } else if (scope.incidentStatus() == IncidentStatus.INVESTIGATING) {
            end = clock.instant();
        } else {
            end = scope.incidentUpdatedAt();
        }
        long seconds = Duration.between(facts.currentRunStartedAt(), end).toSeconds();
        return Math.clamp(seconds, 0, facts.maxDurationSeconds());
    }
}
