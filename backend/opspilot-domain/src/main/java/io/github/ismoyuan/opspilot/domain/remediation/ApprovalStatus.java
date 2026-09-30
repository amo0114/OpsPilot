package io.github.ismoyuan.opspilot.domain.remediation;

/** ApprovalRequest 状态（01 §24、04 §43）：只能从 PENDING 进入一个终态，终态不可反转（04 §44）。 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED;

    public boolean isDecided() {
        return this != PENDING;
    }
}
