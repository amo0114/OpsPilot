package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.time.Instant;
import java.util.List;

/**
 * 故障详情的阶段版本（08 TASK-020：只含已实现的事实）。当前判断、处理建议、恢复情况与 availableActions
 * 由 TASK-085/086 补齐，此处不以占位数据表示调查已完成。
 *
 * @param description 可为空
 * @param resolvedAt 可为空
 * @param investigation 尚未开始调查时为空
 */
public record IncidentDetailView(
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
        List<AffectedResourceView> affectedResources,
        InvestigationRunView investigation) {

    public IncidentDetailView {
        affectedResources = List.copyOf(affectedResources);
    }
}
