package io.github.ismoyuan.opspilot.domain.remediation;

/** RemediationPlan 状态（04 §38）：EXECUTED 只表示已发生执行尝试，不表示成功或 RESOLVED。 */
public enum RemediationPlanStatus {
    ACTIVE,
    SUPERSEDED,
    CANCELLED,
    EXECUTED
}
