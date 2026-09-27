package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.diagnosis;

import java.time.LocalDateTime;

/** diagnosis 行；时间为 UTC。 */
record DiagnosisRow(
        long id,
        long investigationId,
        int runNo,
        int versionNo,
        String conclusionType,
        Long primaryHypothesisId,
        String summary,
        String impactSummary,
        String terminationReason,
        LocalDateTime createdAt) {}
