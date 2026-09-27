package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.List;
import java.util.Objects;

/**
 * DIAGNOSIS_CREATED 载荷：timeline.diagnosis-created / 1。
 *
 * @param primaryHypothesisId 可为空
 */
public record DiagnosisCreatedPayloadV1(
        String incidentKey,
        long investigationId,
        long diagnosisId,
        int versionNo,
        int runNo,
        String conclusionType,
        Long primaryHypothesisId,
        List<Long> evidenceIds,
        String terminationReason)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.diagnosis-created";
    public static final int SCHEMA_VERSION = 1;

    public DiagnosisCreatedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(conclusionType, "conclusionType");
        Objects.requireNonNull(terminationReason, "terminationReason");
        evidenceIds = List.copyOf(evidenceIds);
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
