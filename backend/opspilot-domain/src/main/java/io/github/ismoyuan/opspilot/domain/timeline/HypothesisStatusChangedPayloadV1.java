package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * HYPOTHESIS_STATUS_CHANGED 载荷：timeline.hypothesis-status-changed / 1。
 *
 * @param reason 可为空
 * @param evidenceId 随 Evidence 创建附带的状态变化时为该 Evidence，单独更新时为空
 */
public record HypothesisStatusChangedPayloadV1(
        String incidentKey,
        long investigationId,
        long hypothesisId,
        String previousStatus,
        String newStatus,
        String reason,
        Long evidenceId)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.hypothesis-status-changed";
    public static final int SCHEMA_VERSION = 1;

    public HypothesisStatusChangedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(previousStatus, "previousStatus");
        Objects.requireNonNull(newStatus, "newStatus");
    }

    @Override
    public String schemaName() {
        return SCHEMA_NAME;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }
}
