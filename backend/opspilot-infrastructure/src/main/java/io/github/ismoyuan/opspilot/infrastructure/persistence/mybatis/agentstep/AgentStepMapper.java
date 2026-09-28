package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.agentstep;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 登记与从 RUNNING 条件写入终态；没有删除，也不改写已终结的记录（04 §59）。 */
@Mapper
interface AgentStepMapper {

    /** 不加锁读取该 Investigation 现有最大 step_no（无 Step 时为 0）；调用方须已持有 Investigation 行锁。 */
    int selectMaxStepNo(@Param("investigationId") long investigationId);

    int insertRunning(@Param("s") AgentStepInsert step);

    Long selectIncidentId(@Param("id") long id);

    AgentStepRow selectByIdForUpdate(@Param("id") long id);

    int markSucceeded(
            @Param("id") long id,
            @Param("intentType") String intentType,
            @Param("outputPayload") String outputPayload,
            @Param("modelProvider") String modelProvider,
            @Param("modelName") String modelName,
            @Param("promptTemplateVersion") String promptTemplateVersion,
            @Param("promptTokens") Integer promptTokens,
            @Param("completionTokens") Integer completionTokens,
            @Param("latencyMs") long latencyMs,
            @Param("finishedAt") LocalDateTime finishedAt);

    int markFailed(
            @Param("id") long id,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("latencyMs") long latencyMs,
            @Param("finishedAt") LocalDateTime finishedAt);

    /** 不加锁读取；随后按 id 条件更新，只锁这些行。 */
    List<Long> selectRunningStartedBefore(@Param("startedBefore") LocalDateTime startedBefore);

    int markInterrupted(
            @Param("ids") Collection<Long> ids,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("finishedAt") LocalDateTime finishedAt);
}
