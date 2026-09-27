package io.github.ismoyuan.opspilot.application.investigation.query;

import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;

/** 05 §54：关系本身及两侧摘要；没有版本或更新字段。 */
public record EvidenceView(
        long id,
        EvidenceRelation relation,
        String reason,
        long observationId,
        String observationSummary,
        long hypothesisId,
        String hypothesisTitle) {}
