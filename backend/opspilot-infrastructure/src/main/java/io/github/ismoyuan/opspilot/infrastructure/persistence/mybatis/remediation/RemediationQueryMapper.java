package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.remediation;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface RemediationQueryMapper {

    LatestDiagnosisRow selectLatestDiagnosis(@Param("incidentId") long incidentId);

    List<FrozenEvidenceRow> selectFrozenEvidence(@Param("diagnosisId") long diagnosisId);

    List<Long> selectAffectedResourceIds(@Param("incidentId") long incidentId);

    record LatestDiagnosisRow(long id, int versionNo, String conclusionType, String summary) {}

    record FrozenEvidenceRow(long id, String relation, String observationSummary, long resourceId) {}
}
