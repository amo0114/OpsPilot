package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * INCIDENT_CANCELLED 载荷：timeline.incident-cancelled / 1。
 *
 * @param reason 可为空
 */
public record IncidentCancelledPayloadV1(String incidentKey, String previousStatus, String reason)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.incident-cancelled";
    public static final int SCHEMA_VERSION = 1;

    public IncidentCancelledPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(previousStatus, "previousStatus");
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
