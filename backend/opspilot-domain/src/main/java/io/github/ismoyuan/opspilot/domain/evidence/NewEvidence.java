package io.github.ismoyuan.opspilot.domain.evidence;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.Map;
import java.util.Objects;

/**
 * 待创建的证据关系：某条真实调查 Observation 与某个 Hypothesis 之间的语义关系，不是 Observation 的副本（01 §17、03 §30）。
 * 三者属于同一 Investigation 由创建事务核对（INV-003、04 §31）。reason 上限与 V003 列长度一致。
 */
public record NewEvidence(
        long investigationId, long observationId, long hypothesisId, EvidenceRelation relation, String reason) {

    public static final int REASON_MAX = 1000;

    public NewEvidence {
        Objects.requireNonNull(relation, "relation");
        if (reason == null || reason.isBlank()) {
            throw invalid("BLANK");
        }
        reason = reason.strip();
        if (reason.codePointCount(0, reason.length()) > REASON_MAX) {
            throw invalid("TOO_LONG");
        }
    }

    /** details 只含字段名与原因，不回显输入值。 */
    private static DomainException invalid(String reason) {
        return new DomainException(
                ErrorCode.REQUEST_VALIDATION_FAILED,
                "Invalid evidence field reason: " + reason,
                Map.of("field", "reason", "reason", reason));
    }
}
