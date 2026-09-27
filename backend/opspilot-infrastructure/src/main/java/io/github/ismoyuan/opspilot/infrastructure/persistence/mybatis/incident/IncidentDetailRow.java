package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import java.time.LocalDateTime;

/** 详情投影行；id 只用于继续查询受影响资源。runNo 为空表示尚未开始调查。 */
record IncidentDetailRow(
        long id,
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
        LocalDateTime stopRequestedAt) {}
