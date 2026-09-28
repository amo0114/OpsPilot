package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface InvestigationMapper {

    InvestigationRow selectByIncidentIdForUpdate(@Param("incidentId") long incidentId);

    int insertFirstRun(
            @Param("incidentId") long incidentId,
            @Param("now") LocalDateTime now,
            @Param("maxCapabilityCalls") int maxCapabilityCalls,
            @Param("maxDurationSeconds") int maxDurationSeconds,
            @Param("agentStepTimeoutSeconds") int agentStepTimeoutSeconds,
            @Param("maxConsecutiveAiFailures") int maxConsecutiveAiFailures);

    /** 当前 run 首次 Stop：条件为原轮号、原版本且尚未 Stop。 */
    int requestStop(
            @Param("id") long id,
            @Param("expectedRunNo") int expectedRunNo,
            @Param("expectedVersion") long expectedVersion,
            @Param("stopRequestedAt") LocalDateTime stopRequestedAt,
            @Param("stopRequestedBy") String stopRequestedBy);

    /** run 切换：只改本轮字段与活动时间，条件为原轮号与原版本。 */
    int startNextRun(
            @Param("id") long id,
            @Param("expectedRunNo") int expectedRunNo,
            @Param("expectedVersion") long expectedVersion,
            @Param("nextRunNo") int nextRunNo,
            @Param("now") LocalDateTime now);

    /** 只改本轮连续 AI 失败计数：条件为原轮号与原版本。 */
    int updateAiFailureCount(
            @Param("id") long id,
            @Param("expectedRunNo") int expectedRunNo,
            @Param("expectedVersion") long expectedVersion,
            @Param("count") int count,
            @Param("updatedAt") LocalDateTime updatedAt);
}
