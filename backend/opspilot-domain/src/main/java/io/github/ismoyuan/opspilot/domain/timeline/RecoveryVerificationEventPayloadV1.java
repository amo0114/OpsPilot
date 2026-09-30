package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * RECOVERY_VERIFICATION_STARTED / PASSED / FAILED / INCONCLUSIVE 载荷：timeline.recovery-verification / 1（01 §35）。只含引用与
 * 整体结果，逐项检查在 Verification 的 result_payload 中。
 *
 * @param overallResult 终态事件的 PASSED/FAILED/INCONCLUSIVE；STARTED 为空
 */
public record RecoveryVerificationEventPayloadV1(
        String incidentKey, long verificationId, int verificationNo, String overallResult) implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.recovery-verification";
    public static final int SCHEMA_VERSION = 1;

    public RecoveryVerificationEventPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        if (verificationNo < 1) {
            throw new IllegalArgumentException("verificationNo must be >= 1");
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
