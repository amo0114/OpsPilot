package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/** INCIDENT_RESOLVED 载荷：timeline.incident-resolved / 1（01 §31、§35），引用证明恢复的 PASSED Verification。 */
public record IncidentResolvedPayloadV1(String incidentKey, long verificationId, int verificationNo)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.incident-resolved";
    public static final int SCHEMA_VERSION = 1;

    public IncidentResolvedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
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
