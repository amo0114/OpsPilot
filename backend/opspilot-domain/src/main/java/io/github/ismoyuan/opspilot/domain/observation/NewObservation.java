package io.github.ismoyuan.opspilot.domain.observation;

import java.time.Instant;
import java.util.Objects;

/**
 * 待写入的不可变观测（01 §15、04 §22～§26）。只能来自一次成功的 CapabilityInvocation，与其 Incident、上下文、资源一致；
 * 调查 run 或恢复样本身份经 capabilityInvocationId 追溯，不在此复制。
 *
 * @param investigationId 调查观测时非空，与 recoveryVerificationId 互斥
 * @param recoveryVerificationId 恢复观测时非空
 * @param payload 按 schemaName/schemaVersion 解释的 JSON 对象文本，由对应 Codec 解码（TASK-051 起）
 * @param windowStart 可为空，与 windowEnd 成对
 */
public record NewObservation(
        long incidentId,
        Long investigationId,
        Long recoveryVerificationId,
        long capabilityInvocationId,
        long managedResourceId,
        ObservationKind kind,
        String schemaName,
        int schemaVersion,
        String payload,
        String summary,
        Instant observedAt,
        Instant windowStart,
        Instant windowEnd) {

    /** 与 V003 列长度一致。 */
    public static final int SUMMARY_MAX = 1000;

    public NewObservation {
        if ((investigationId == null) == (recoveryVerificationId == null)) {
            throw new IllegalArgumentException(
                    "observation must belong to exactly one of investigation or verification");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(observedAt, "observedAt");
        if (schemaName.isBlank() || schemaVersion < 1) {
            throw new IllegalArgumentException("observation schema must be named and versioned");
        }
        if (summary.isBlank() || summary.codePointCount(0, summary.length()) > SUMMARY_MAX) {
            throw new IllegalArgumentException("observation summary must be non-blank and at most " + SUMMARY_MAX);
        }
        if ((windowStart == null) != (windowEnd == null) || (windowStart != null && windowStart.isAfter(windowEnd))) {
            throw new IllegalArgumentException("observation window must be complete and ordered");
        }
    }
}
