package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.timeline;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只有插入；时间线不提供更新或删除语句（01 §34）。 */
@Mapper
interface TimelineEventMapper {

    int insert(@Param("event") TimelineEventInsert event);
}
