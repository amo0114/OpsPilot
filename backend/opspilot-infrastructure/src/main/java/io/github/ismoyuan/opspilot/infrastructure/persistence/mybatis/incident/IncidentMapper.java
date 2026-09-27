package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import java.time.LocalDateTime;
import java.util.Collection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface IncidentMapper {

    IncidentRow selectById(@Param("id") long id);

    IncidentRow selectByKey(@Param("incidentKey") String incidentKey);

    /** 条件更新失败后读取最新已提交版本以区分冲突原因（共享锁读，不受事务快照影响）。 */
    IncidentRow selectByIdForShare(@Param("id") long id);

    int selectLastSequence(@Param("dayPrefix") String dayPrefix);

    /** 以 CREATED、lock_version 0 插入；状态字面量固定在 SQL 中。 */
    int insertCreated(
            @Param("incidentKey") String incidentKey,
            @Param("managedSystemId") long managedSystemId,
            @Param("title") String title,
            @Param("description") String description,
            @Param("impactSummary") String impactSummary,
            @Param("createdSource") String createdSource,
            @Param("createdBy") String createdBy,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("detectedAt") LocalDateTime detectedAt,
            @Param("createdAt") LocalDateTime createdAt);

    int insertAffectedResources(
            @Param("incidentId") long incidentId,
            @Param("managedResourceIds") Collection<Long> managedResourceIds,
            @Param("createdAt") LocalDateTime createdAt);

    /** 唯一的状态写入语句：WHERE id AND status AND lock_version（04 §14）。 */
    int transition(
            @Param("id") long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("targetStatus") String targetStatus,
            @Param("resolvedAt") LocalDateTime resolvedAt,
            @Param("updatedAt") LocalDateTime updatedAt);
}
