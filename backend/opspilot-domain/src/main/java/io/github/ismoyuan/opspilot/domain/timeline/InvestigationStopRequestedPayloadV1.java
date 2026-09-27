package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/** INVESTIGATION_STOP_REQUESTED 载荷：timeline.investigation-stop-requested / 1。 */
public record InvestigationStopRequestedPayloadV1(String incidentKey, int runNo) implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.investigation-stop-requested";
    public static final int SCHEMA_VERSION = 1;

    public InvestigationStopRequestedPayloadV1 {
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
