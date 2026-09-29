package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * CAPABILITY_REQUEST_REJECTED 载荷：timeline.capability-request-rejected / 1。只含能力、资源编号与拒绝码/原因码，不含请求参数原文。
 *
 * @param reason 机器可读的原因码（如 RECENT_OR_IN_FLIGHT）
 */
public record CapabilityRequestRejectedPayloadV1(
        String incidentKey,
        long investigationId,
        int runNo,
        String capabilityKey,
        long resourceId,
        String errorCode,
        String reason)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.capability-request-rejected";
    public static final int SCHEMA_VERSION = 1;

    public CapabilityRequestRejectedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(reason, "reason");
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
