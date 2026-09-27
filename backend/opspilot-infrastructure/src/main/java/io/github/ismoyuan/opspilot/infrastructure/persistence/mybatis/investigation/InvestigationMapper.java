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

    /** run 切换：只改本轮字段与活动时间，条件为原轮号与原版本。 */
    int startNextRun(
            @Param("id") long id,
            @Param("expectedRunNo") int expectedRunNo,
            @Param("expectedVersion") long expectedVersion,
            @Param("nextRunNo") int nextRunNo,
            @Param("now") LocalDateTime now);
}
