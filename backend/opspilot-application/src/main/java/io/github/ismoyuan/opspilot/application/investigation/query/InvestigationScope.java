package io.github.ismoyuan.opspilot.application.investigation.query;

import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.time.Instant;

/**
 * 按 incidentKey 定位到的 Incident 与其唯一 Investigation。
 *
 * @param investigationId 尚未开始调查时为空
 */
public record InvestigationScope(
        long incidentId, IncidentStatus incidentStatus, Instant incidentUpdatedAt, Long investigationId) {}
