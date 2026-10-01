package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import java.time.LocalDateTime;

/**
 * 详情 Snapshot 投影行（同一条查询）；时间为 UTC。runNo 为空表示尚未开始调查；lastTimelineEventId 没有事件为 0。
 */
record IncidentDetailRow(
        long id,
        long managedSystemId,
        String incidentKey,
        String title,
        String description,
        String systemKey,
        String systemName,
        String status,
        long lockVersion,
        String impactSummary,
        LocalDateTime startedAt,
        LocalDateTime detectedAt,
        LocalDateTime resolvedAt,
        Integer runNo,
        LocalDateTime stopRequestedAt,
        long lastTimelineEventId) {}
