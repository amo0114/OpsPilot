package io.github.ismoyuan.opspilot.domain.timeline;

import io.github.ismoyuan.opspilot.domain.incident.IncidentSource;
import java.util.List;
import java.util.Objects;

/** INCIDENT_CREATED 载荷：timeline.incident-created / 1。 */
public record IncidentCreatedPayloadV1(
        String incidentKey, String systemKey, IncidentSource createdSource, List<String> affectedResourceKeys)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.incident-created";
    public static final int SCHEMA_VERSION = 1;

    public IncidentCreatedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(systemKey, "systemKey");
        Objects.requireNonNull(createdSource, "createdSource");
        affectedResourceKeys = List.copyOf(affectedResourceKeys);
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
