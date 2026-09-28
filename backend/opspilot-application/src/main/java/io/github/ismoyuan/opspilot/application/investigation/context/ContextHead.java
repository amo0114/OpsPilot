package io.github.ismoyuan.opspilot.application.investigation.context;

import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.time.Instant;

/** 上下文构造的起点：Incident 摘要与其 Investigation 的当前 run 控制字段。 */
public record ContextHead(
        long incidentId,
        String incidentKey,
        String title,
        String impactSummary,
        Instant startedAt,
        IncidentStatus status,
        long investigationId,
        int currentRunNo,
        Instant currentRunStartedAt,
        int currentRunCapabilityCount,
        int maxCapabilityCalls,
        int maxDurationSeconds) {}
