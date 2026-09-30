package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.remediation;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 方案创建与审批决定：插入、加锁读取与只从 PENDING 出发的条件更新（04 §43～§44、§77～§78）。 */
@Mapper
interface ApprovalMapper {

    int insertPlan(
            @Param("key") GeneratedKey key,
            @Param("incidentId") long incidentId,
            @Param("diagnosisId") long diagnosisId,
            @Param("title") String title,
            @Param("summary") String summary,
            @Param("at") LocalDateTime at);

    int insertAction(
            @Param("key") GeneratedKey key,
            @Param("planId") long planId,
            @Param("capabilityKey") String capabilityKey,
            @Param("targetResourceId") long targetResourceId,
            @Param("schemaName") String schemaName,
            @Param("schemaVersion") int schemaVersion,
            @Param("payload") String payload,
            @Param("summary") String summary,
            @Param("expectedImpactSummary") String expectedImpactSummary,
            @Param("riskLevel") String riskLevel,
            @Param("requiresApproval") boolean requiresApproval,
            @Param("at") LocalDateTime at);

    int insertApproval(@Param("key") GeneratedKey key, @Param("actionId") long actionId, @Param("at") LocalDateTime at);

    Long selectIncidentId(@Param("approvalId") long approvalId);

    LockedApprovalRow selectForUpdate(@Param("approvalId") long approvalId);

    int updateDecision(
            @Param("approvalId") long approvalId,
            @Param("status") String status,
            @Param("decidedBy") String decidedBy,
            @Param("decidedAt") LocalDateTime decidedAt,
            @Param("comment") String comment,
            @Param("expectedVersion") long expectedVersion);

    int cancelActivePlan(@Param("planId") long planId, @Param("at") LocalDateTime at);

    ApprovalViewRow selectView(@Param("approvalId") long approvalId);

    record LockedApprovalRow(
            long id,
            long actionId,
            String status,
            LocalDateTime requestedAt,
            String decidedBy,
            LocalDateTime decidedAt,
            String comment,
            long lockVersion,
            long planId,
            String planStatus,
            long diagnosisId,
            String capabilityKey,
            long targetResourceId) {}

    record ApprovalViewRow(
            long id,
            String status,
            long lockVersion,
            String incidentKey,
            long actionId,
            String actionSummary,
            String capabilityKey,
            String resourceKey,
            String resourceName,
            String riskLevel,
            String expectedImpactSummary,
            LocalDateTime requestedAt,
            String decidedBy,
            LocalDateTime decidedAt,
            String comment) {}
}
