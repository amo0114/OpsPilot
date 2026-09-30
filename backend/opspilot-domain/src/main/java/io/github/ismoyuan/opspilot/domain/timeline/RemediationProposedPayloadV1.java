package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/** REMEDIATION_PROPOSED 载荷（AI 建议经 Java 校验后形成的 Plan/Action，风险来自 Registry）：timeline.remediation-proposed / 1。 */
public record RemediationProposedPayloadV1(
        String incidentKey,
        long diagnosisId,
        long planId,
        long actionId,
        String capabilityKey,
        String resourceKey,
        String riskLevel)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.remediation-proposed";
    public static final int SCHEMA_VERSION = 1;

    public RemediationProposedPayloadV1 {
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
