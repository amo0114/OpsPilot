package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/** HYPOTHESIS_CREATED 载荷：timeline.hypothesis-created / 1。初始状态固定为 PENDING，不单独记录。 */
public record HypothesisCreatedPayloadV1(String incidentKey, long investigationId, long hypothesisId, String title)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.hypothesis-created";
    public static final int SCHEMA_VERSION = 1;

    public HypothesisCreatedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(title, "title");
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
