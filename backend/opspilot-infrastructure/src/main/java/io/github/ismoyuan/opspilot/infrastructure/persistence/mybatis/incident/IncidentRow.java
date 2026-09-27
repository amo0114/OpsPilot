package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import java.time.LocalDateTime;

/** incident 行；时间为 UTC 的 LocalDateTime（DATETIME(3) 不带时区，不经 JVM 默认时区换算）。 */
record IncidentRow(
        long id,
        String incidentKey,
        long managedSystemId,
        String title,
        String description,
        String impactSummary,
        String status,
        String createdSource,
        String createdBy,
        LocalDateTime startedAt,
        LocalDateTime detectedAt,
        LocalDateTime resolvedAt,
        long lockVersion) {}
