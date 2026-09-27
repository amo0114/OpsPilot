package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.hypothesis;

import java.time.LocalDateTime;

/** hypothesis 行；时间为 UTC。 */
record HypothesisRow(
        long id,
        long investigationId,
        String title,
        String description,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long lockVersion) {}
