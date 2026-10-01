package io.github.ismoyuan.opspilot.application.timeline.query;

import io.github.ismoyuan.opspilot.application.incident.IncidentLocks;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 时间线读取（08 TASK-084、05 §60）：Timeline 只追加，以 afterId 游标而不是页码分段读取。同一 Incident 的追加都先取该 Incident 行锁
 * （04 §57），已提交事件的 id 顺序即追加顺序，游标不会跳过之后才提交的较小 id。
 */
@Service
public class TimelineQueryService {

    private final TimelineQueryRepository repository;

    public TimelineQueryService(TimelineQueryRepository repository) {
        this.repository = repository;
    }

    /** afterId、limit 已由 web 边界校验。 */
    @Transactional(readOnly = true)
    public TimelinePage listEvents(String incidentKey, long afterId, int limit) {
        long incidentId = repository.findIncidentId(incidentKey).orElseThrow(() -> IncidentLocks.notFound(incidentKey));
        List<TimelineEventView> events = repository.findAfter(incidentId, afterId, limit);
        return new TimelinePage(
                events, events.isEmpty() ? afterId : events.getLast().id());
    }
}
