package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/** CAPABILITY_INVOKED 载荷：timeline.capability-invoked / 1。 */
public record CapabilityInvokedPayloadV1(
        String incidentKey,
        long investigationId,
        int runNo,
        long invocationId,
        String capabilityKey,
        String resourceKey)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.capability-invoked";
    public static final int SCHEMA_VERSION = 1;

    public CapabilityInvokedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(resourceKey, "resourceKey");
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
