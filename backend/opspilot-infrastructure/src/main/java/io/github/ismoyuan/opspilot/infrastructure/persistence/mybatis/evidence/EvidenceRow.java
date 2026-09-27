package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.evidence;

import java.time.LocalDateTime;

/** evidence 行；时间为 UTC。 */
record EvidenceRow(
        long id,
        long investigationId,
        long observationId,
        long hypothesisId,
        String relation,
        String reason,
        LocalDateTime createdAt) {}
