package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.observation;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只有插入与查询；observation 不存在 UPDATE/DELETE 语句（04 §26）。 */
@Mapper
interface ObservationMapper {

    /** @return 插入行数：来源 Invocation 不满足条件时为 0 */
    int insertFromSucceededInvocation(@Param("o") ObservationInsert observation);

    ObservationRow selectById(@Param("id") long id);

    List<ObservationRow> selectByInvestigationId(@Param("investigationId") long investigationId);
}
