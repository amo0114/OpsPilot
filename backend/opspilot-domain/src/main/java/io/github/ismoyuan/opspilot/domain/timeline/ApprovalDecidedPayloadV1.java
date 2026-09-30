package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/** APPROVAL_REJECTED / APPROVAL_CANCELLED 载荷（comment 可为空）：timeline.approval-decided / 1。 */
public record ApprovalDecidedPayloadV1(
        String incidentKey, long approvalId, long actionId, String decision, String comment)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.approval-decided";
    public static final int SCHEMA_VERSION = 1;

    public ApprovalDecidedPayloadV1 {
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
