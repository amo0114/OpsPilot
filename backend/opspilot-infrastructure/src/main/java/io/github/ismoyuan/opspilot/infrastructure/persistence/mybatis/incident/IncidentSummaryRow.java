package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import java.time.LocalDateTime;

/** 列表投影行；时间为 UTC。 */
record IncidentSummaryRow(
        String incidentKey,
        String title,
        String systemName,
        String status,
        String impactSummary,
        LocalDateTime detectedAt,
        LocalDateTime updatedAt) {}
