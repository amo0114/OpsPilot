package io.github.ismoyuan.opspilot.application.investigation.query;

import java.util.List;
import java.util.Optional;

/**
 * 调查技术详情的只读 SQL 投影（05 §49～§56、07 §24）。只读取某一 Investigation 下的调查事实：
 * 恢复 Observation 不出现在调查列表与详情中。编号按字节精确匹配。
 */
public interface InvestigationQueryRepository {

    Optional<InvestigationScope> findScope(String incidentKey);

    InvestigationFacts findFacts(long investigationId);

    /** 按 id 升序。 */
    List<HypothesisView> findHypotheses(long investigationId);

    long countObservations(long investigationId, ObservationFilter filter);

    /** 按 id 升序。 */
    List<ObservationSummaryView> findObservations(
            long investigationId, ObservationFilter filter, int offset, int limit);

    Optional<ObservationDetailView> findObservation(long investigationId, long observationId);

    /** 按 id 升序。 */
    List<EvidenceView> findEvidence(long investigationId);

    /** 按版本升序。 */
    List<DiagnosisSummaryView> findDiagnoses(long investigationId);

    Optional<DiagnosisDetailView> findDiagnosis(long investigationId, int version);
}
