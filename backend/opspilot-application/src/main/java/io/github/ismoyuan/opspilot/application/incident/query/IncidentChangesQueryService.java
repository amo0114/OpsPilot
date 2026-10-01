package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.application.incident.IncidentLocks;
import io.github.ismoyuan.opspilot.application.timeline.query.TimelineQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SSE 发送端读取的事实（08 TASK-087～089）：每次读取都是新的只读事务，只能看到已提交的数据（ENG-INV-016）。事件只按
 * (incident_id, id) 游标读取；同一 Incident 的追加都先取该 Incident 行锁（04 §57），已提交事件的 id 顺序即提交顺序，游标之后不会再
 * 出现更小的 id。查询路径不修改任何状态。
 */
@Service
public class IncidentChangesQueryService {

    private final IncidentQueryRepository incidents;
    private final TimelineQueryRepository timeline;
    private final AvailableActionsResolver actions;

    public IncidentChangesQueryService(
            IncidentQueryRepository incidents, TimelineQueryRepository timeline, AvailableActionsResolver actions) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.actions = actions;
    }

    /** @throws io.github.ismoyuan.opspilot.application.error.ApplicationException INCIDENT_NOT_FOUND */
    @Transactional(readOnly = true)
    public long incidentId(String incidentKey) {
        return timeline.findIncidentId(incidentKey).orElseThrow(() -> IncidentLocks.notFound(incidentKey));
    }

    /**
     * 状态与事件来自同一快照：发送完这些事件后的状态正是该快照的状态。
     *
     * @throws io.github.ismoyuan.opspilot.application.error.ApplicationException INCIDENT_NOT_FOUND
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IncidentChanges read(String incidentKey, long afterId, int limit) {
        IncidentSnapshot incident =
                incidents.findSnapshot(incidentKey).orElseThrow(() -> IncidentLocks.notFound(incidentKey));
        return new IncidentChanges(
                new IncidentStateView(
                        incident.incidentKey(), incident.status(), incident.version(), actions.resolve(incident)),
                timeline.findAfter(incident.incidentId(), afterId, limit));
    }
}
