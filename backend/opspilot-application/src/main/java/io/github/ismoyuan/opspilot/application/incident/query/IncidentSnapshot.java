package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.time.Instant;
import java.util.List;

/**
 * 详情的基础快照（05 §65、§67、08 TASK-085）：Incident 状态、版本、当前 runNo、stopRequested 与 lastTimelineEventId 来自同一条
 * 查询。内部 id 只用于在同一只读事务内继续读取各节，不对外输出。
 *
 * @param runNo 尚未开始调查为空
 * @param lastTimelineEventId 该 Incident 已提交的最大 Timeline id；没有事件为 0
 */
public record IncidentSnapshot(
        long incidentId,
        long managedSystemId,
        String incidentKey,
        String title,
        String description,
        String systemKey,
        String systemName,
        IncidentStatus status,
        long version,
        String impactSummary,
        Instant startedAt,
        Instant detectedAt,
        Instant resolvedAt,
        Integer runNo,
        boolean stopRequested,
        long lastTimelineEventId,
        List<AffectedResourceView> affectedResources) {

    public IncidentSnapshot {
        affectedResources = List.copyOf(affectedResources);
    }
}
