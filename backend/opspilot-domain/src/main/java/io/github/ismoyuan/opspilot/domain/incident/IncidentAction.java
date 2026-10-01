package io.github.ismoyuan.opspilot.domain.incident;

/**
 * V0.1 公开的 Incident 动作（05 §13）；顺序即返回顺序。Approval 的 APPROVE/REJECT/CANCEL 属于审批对象，不在此列。
 */
public enum IncidentAction {
    START_INVESTIGATION,
    STOP_INVESTIGATION,
    CONTINUE_INVESTIGATION,
    REQUEST_REMEDIATION,
    VERIFY_RECOVERY,
    CANCEL_INCIDENT
}
