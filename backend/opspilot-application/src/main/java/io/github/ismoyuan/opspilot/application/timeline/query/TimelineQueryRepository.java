package io.github.ismoyuan.opspilot.application.timeline.query;

import java.util.List;
import java.util.OptionalLong;

/** 时间线只读投影（07 §24 TimelineQueryRepository）；不修改任何状态，编号按字节精确匹配。 */
public interface TimelineQueryRepository {

    /** Incident 内部 id；编号不存在时为空。 */
    OptionalLong findIncidentId(String incidentKey);

    /** 该 Incident 中 id 大于 afterId 的事件，按 id 升序，最多 limit 条（走 INDEX(incident_id, id)，04 §57）。 */
    List<TimelineEventView> findAfter(long incidentId, long afterId, int limit);
}
