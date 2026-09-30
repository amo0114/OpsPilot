package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/** APPROVAL_REQUESTED 载荷：timeline.approval-requested / 1。 */
public record ApprovalRequestedPayloadV1(String incidentKey, long approvalId, long actionId)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.approval-requested";
    public static final int SCHEMA_VERSION = 1;

    public ApprovalRequestedPayloadV1 {
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
