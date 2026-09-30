package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/**
 * ACTION_EXECUTION_RECONCILIATION_ATTEMPTED 载荷：timeline.action-execution-reconciliation / 1（04 §82）。
 *
 * @param attemptNo 本次登记的核对序号，从 1 开始
 * @param maxAttempts Execution 创建时快照的次数上限
 */
public record ActionExecutionReconciliationPayloadV1(
        String incidentKey, long executionId, long actionId, int attemptNo, int maxAttempts)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.action-execution-reconciliation";
    public static final int SCHEMA_VERSION = 1;

    public ActionExecutionReconciliationPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        if (attemptNo < 1 || attemptNo > maxAttempts) {
            throw new IllegalArgumentException("attemptNo must be within 1..maxAttempts");
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
