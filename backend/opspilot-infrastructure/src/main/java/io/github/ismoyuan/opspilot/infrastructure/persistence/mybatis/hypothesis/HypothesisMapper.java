package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.hypothesis;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只有插入、查询与状态条件更新；没有删除，也不改写标题/描述（03 §38）。 */
@Mapper
interface HypothesisMapper {

    /** 以 PENDING、lock_version 0 插入；状态字面量固定在 SQL 中。 */
    int insertPending(@Param("h") HypothesisInsert hypothesis);

    HypothesisRow selectById(@Param("id") long id);

    HypothesisRow selectByIdForUpdate(@Param("id") long id);

    /** 唯一的状态写入语句：WHERE id AND investigation_id AND status AND lock_version。 */
    int changeStatus(
            @Param("id") long id,
            @Param("investigationId") long investigationId,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("targetStatus") String targetStatus,
            @Param("updatedAt") LocalDateTime updatedAt);
}
