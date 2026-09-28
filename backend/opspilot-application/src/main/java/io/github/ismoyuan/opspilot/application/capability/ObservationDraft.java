package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import java.time.Instant;
import java.util.Objects;

/**
 * 由确定性提取得到的、已脱敏的 Observation 内容（06 §29～§31）；来源 Invocation 的上下文（Incident、调查或恢复、资源）由结果事务补齐。
 *
 * @param windowStart 与 windowEnd 同时为空或同时给出
 */
public record ObservationDraft(
        ObservationKind kind,
        String schemaName,
        int schemaVersion,
        String payload,
        String summary,
        Instant observedAt,
        Instant windowStart,
        Instant windowEnd) {

    public ObservationDraft {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
