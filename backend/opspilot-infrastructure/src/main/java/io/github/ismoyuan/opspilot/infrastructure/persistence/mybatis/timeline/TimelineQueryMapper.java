package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.timeline;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 时间线页面投影（07 §24）；只有查询。 */
@Mapper
interface TimelineQueryMapper {

    Long selectIncidentId(@Param("incidentKey") String incidentKey);

    List<TimelineEventRow> selectAfter(
            @Param("incidentId") long incidentId, @Param("afterId") long afterId, @Param("limit") int limit);

    /** 时间为 UTC。 */
    record TimelineEventRow(long id, String eventType, LocalDateTime occurredAt, String actorType, String summary) {}
}
