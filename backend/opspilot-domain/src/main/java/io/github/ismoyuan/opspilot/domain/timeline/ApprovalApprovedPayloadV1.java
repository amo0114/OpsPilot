package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * APPROVAL_APPROVED 载荷：timeline.approval-approved / 1。批准同事务创建的 PENDING Execution 及其冻结的恢复策略版本（04 §78）；
 * comment 可为空。
 */
public record ApprovalApprovedPayloadV1(
        String incidentKey,
        long approvalId,
        long actionId,
        long executionId,
        long recoveryPolicyId,
        int recoveryPolicyVersion,
        String comment)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.approval-approved";
    public static final int SCHEMA_VERSION = 1;

    public ApprovalApprovedPayloadV1 {
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
