package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import io.github.ismoyuan.opspilot.application.incident.query.AffectedResourceView;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface IncidentQueryMapper {

    long countIncidents(@Param("systemKey") String systemKey, @Param("status") String status);

    List<IncidentSummaryRow> selectIncidents(
            @Param("systemKey") String systemKey,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit);

    IncidentDetailRow selectDetail(@Param("incidentKey") String incidentKey);

    List<AffectedResourceView> selectAffectedResources(@Param("incidentId") long incidentId);

    DiagnosisRow selectLatestDiagnosis(@Param("incidentId") long incidentId);

    List<String> selectSupportingSummaries(@Param("diagnosisId") long diagnosisId);

    RemediationRow selectLatestRemediation(@Param("incidentId") long incidentId);

    /** 时间为 UTC；terminationReason 可为空。 */
    record DiagnosisRow(
            long id,
            int versionNo,
            int runNo,
            String conclusionType,
            String summary,
            String terminationReason,
            LocalDateTime createdAt) {}

    /** 一个 Plan 恰有一个 Action（04 §38）；审批与执行可为空。时间为 UTC。 */
    record RemediationRow(
            long planId,
            String planTitle,
            String planSummary,
            String planStatus,
            LocalDateTime planCreatedAt,
            long actionId,
            String capabilityKey,
            String targetResourceKey,
            String targetResourceName,
            String actionSummary,
            String riskLevel,
            String expectedImpactSummary,
            boolean requiresApproval,
            Long approvalId,
            String approvalStatus,
            Long approvalVersion,
            LocalDateTime requestedAt,
            LocalDateTime decidedAt,
            Long executionId,
            String executionStatus,
            LocalDateTime executionStartedAt,
            LocalDateTime executionFinishedAt,
            String executionErrorCode) {}
}
