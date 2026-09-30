package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * ACTION_EXECUTION_STARTED / SUCCEEDED / FAILED 载荷：timeline.action-execution / 1（01 §35）。
 *
 * @param phase ADMISSION（RUNNING 准入前，未发出 CHANGE）或 EXECUTION（已发出一次 CHANGE）
 * @param errorCode 失败时的错误码，其余为空
 */
public record ActionExecutionEventPayloadV1(
        String incidentKey, long executionId, long actionId, String phase, String errorCode)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.action-execution";
    public static final int SCHEMA_VERSION = 1;
    public static final String ADMISSION = "ADMISSION";
    public static final String EXECUTION = "EXECUTION";

    public ActionExecutionEventPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        if (!ADMISSION.equals(phase) && !EXECUTION.equals(phase)) {
            throw new IllegalArgumentException("phase must be ADMISSION or EXECUTION");
        }
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
