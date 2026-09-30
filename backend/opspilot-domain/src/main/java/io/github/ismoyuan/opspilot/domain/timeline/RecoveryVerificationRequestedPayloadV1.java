package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * RECOVERY_VERIFICATION_REQUESTED 载荷：timeline.recovery-verification-requested / 1（05 §34）。
 *
 * @param note 用户说明，可为空
 */
public record RecoveryVerificationRequestedPayloadV1(
        String incidentKey,
        long verificationId,
        int verificationNo,
        String resourceKey,
        long recoveryPolicyId,
        int recoveryPolicyVersion,
        String note)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.recovery-verification-requested";
    public static final int SCHEMA_VERSION = 1;

    public RecoveryVerificationRequestedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
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
