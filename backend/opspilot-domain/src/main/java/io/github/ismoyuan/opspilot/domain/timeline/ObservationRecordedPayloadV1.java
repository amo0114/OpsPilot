package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * OBSERVATION_RECORDED 载荷：timeline.observation-recorded / 1。只含引用与类型，Observation 载荷不复制进时间线。
 *
 * @param investigationId 恢复采样调用为空
 */
public record ObservationRecordedPayloadV1(
        String incidentKey,
        Long investigationId,
        long invocationId,
        long observationId,
        String observationKind,
        String resourceKey)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.observation-recorded";
    public static final int SCHEMA_VERSION = 1;

    public ObservationRecordedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(observationKind, "observationKind");
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
