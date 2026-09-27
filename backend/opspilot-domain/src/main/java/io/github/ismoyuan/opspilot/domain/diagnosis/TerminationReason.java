package io.github.ismoyuan.opspilot.domain.diagnosis;

/** 本轮调查实际收束的原因（01 §11、04 §35），随 Diagnosis 冻结。 */
public enum TerminationReason {
    AGENT_COMPLETED,
    USER_STOPPED,
    CAPABILITY_BUDGET_EXHAUSTED,
    INVESTIGATION_TIMEOUT,
    AI_RUNTIME_UNAVAILABLE
}
