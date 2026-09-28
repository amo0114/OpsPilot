package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationContextRows.DiagnosisRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationContextRows.EvidenceRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationContextRows.HeadRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationContextRows.HypothesisRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationContextRows.ObservationRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationContextRows.ResourceRow;
import io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation.InvestigationContextRows.TimelineRow;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** AI 上下文只读投影（08 TASK-037）：只有 SELECT，且只选列出的列，不读 payload、凭证或 Fault Lab 数据。 */
@Mapper
interface InvestigationContextMapper {

    HeadRow selectHead(@Param("incidentId") long incidentId);

    List<ResourceRow> selectAffectedResources(@Param("incidentId") long incidentId);

    List<HypothesisRow> selectHypotheses(@Param("investigationId") long investigationId);

    List<EvidenceRow> selectContextEvidence(
            @Param("investigationId") long investigationId, @Param("runStartedAt") LocalDateTime runStartedAt);

    List<ObservationRow> selectContextObservations(
            @Param("investigationId") long investigationId,
            @Param("runNo") int runNo,
            @Param("referenced") Collection<Long> referenced);

    DiagnosisRow selectLatestDiagnosis(@Param("investigationId") long investigationId);

    List<Long> selectDiagnosisEvidenceIds(@Param("diagnosisId") long diagnosisId);

    List<TimelineRow> selectRecentTimeline(
            @Param("incidentId") long incidentId,
            @Param("eventTypes") Collection<String> eventTypes,
            @Param("limit") int limit);
}
