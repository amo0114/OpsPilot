package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * CAPABILITY_FAILED 载荷：timeline.capability-failed / 1。
 *
 * @param investigationId 恢复采样调用为空
 * @param errorCode 06 §35 错误码；错误文案不入载荷
 */
public record CapabilityFailedPayloadV1(
        String incidentKey,
        Long investigationId,
        long invocationId,
        String capabilityKey,
        String resourceKey,
        String errorCode)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.capability-failed";
    public static final int SCHEMA_VERSION = 1;

    public CapabilityFailedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(resourceKey, "resourceKey");
        Objects.requireNonNull(errorCode, "errorCode");
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
