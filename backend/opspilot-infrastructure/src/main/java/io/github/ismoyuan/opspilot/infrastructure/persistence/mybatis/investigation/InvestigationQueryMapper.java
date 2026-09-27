package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import io.github.ismoyuan.opspilot.application.investigation.query.EvidenceView;
import io.github.ismoyuan.opspilot.application.investigation.query.HypothesisView;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationQueryRows.DiagnosisRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationQueryRows.DiagnosisSummaryRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationQueryRows.FactsRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationQueryRows.ObservationRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationQueryRows.ScopeRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 调查技术详情只读投影（05 §49～§56）；只有 SELECT。 */
@Mapper
interface InvestigationQueryMapper {

    ScopeRow selectScope(@Param("incidentKey") String incidentKey);

    FactsRow selectFacts(@Param("investigationId") long investigationId);

    List<HypothesisView> selectHypotheses(@Param("investigationId") long investigationId);

    long countObservations(
            @Param("investigationId") long investigationId,
            @Param("resourceKey") String resourceKey,
            @Param("kind") String kind);

    List<ObservationRow> selectObservations(
            @Param("investigationId") long investigationId,
            @Param("resourceKey") String resourceKey,
            @Param("kind") String kind,
            @Param("offset") int offset,
            @Param("limit") int limit);

    ObservationRow selectObservation(
            @Param("investigationId") long investigationId, @Param("observationId") long observationId);

    List<EvidenceView> selectEvidence(@Param("investigationId") long investigationId);

    List<DiagnosisSummaryRow> selectDiagnoses(@Param("investigationId") long investigationId);

    DiagnosisRow selectDiagnosis(@Param("investigationId") long investigationId, @Param("version") int version);

    /** 该 Diagnosis 创建时冻结引用的 Evidence（diagnosis_evidence_ref），按 id 升序。 */
    List<EvidenceView> selectDiagnosisEvidence(@Param("diagnosisId") long diagnosisId);
}
