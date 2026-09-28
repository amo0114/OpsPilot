package io.github.ismoyuan.opspilot.domain.agentstep;

/** 一次 AI 决策步骤的运行状态（04 §59）：终态只从 RUNNING 条件更新。 */
public enum AgentStepStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}
