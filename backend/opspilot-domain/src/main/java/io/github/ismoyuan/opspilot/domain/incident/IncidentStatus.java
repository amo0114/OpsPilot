package io.github.ismoyuan.opspilot.domain.incident;

/** V0.1 冻结的 8 个故障状态（01 §2～§3），不得增删；Stop 不是状态（01 §4）。 */
public enum IncidentStatus {
    CREATED,
    INVESTIGATING,
    DIAGNOSED,
    AWAITING_APPROVAL,
    EXECUTING,
    VERIFYING,
    RESOLVED,
    CANCELLED;

    /** RESOLVED 与 CANCELLED 为终态，不支持 reopen（01 §32～§33）。 */
    public boolean isTerminal() {
        return this == RESOLVED || this == CANCELLED;
    }
}
