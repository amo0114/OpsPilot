package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * INVESTIGATION_STARTED 载荷：timeline.investigation-started / 1。记录来源、旧轮号、新轮号和本轮预算（01 §9）。
 *
 * @param source 进入调查的迁移触发，如 START_INVESTIGATION、CONTINUE_INVESTIGATION
 * @param previousRunNo 首次开始为 0
 */
public record InvestigationStartedPayloadV1(
        String incidentKey, String source, int previousRunNo, int runNo, int maxCapabilityCalls, int maxDurationSeconds)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.investigation-started";
    public static final int SCHEMA_VERSION = 1;

    public InvestigationStartedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(source, "source");
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
