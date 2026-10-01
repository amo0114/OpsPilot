package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.application.timeline.query.TimelineEventView;
import java.util.List;

/**
 * 同一一致性读取中的 Incident 当前状态与游标之后的已提交事件（05 §64～§65）。
 *
 * @param events 按 id 升序，最多请求的条数；条数等于上限时可能还有更多
 */
public record IncidentChanges(IncidentStateView state, List<TimelineEventView> events) {

    public IncidentChanges {
        events = List.copyOf(events);
    }
}
