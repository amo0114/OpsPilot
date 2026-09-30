package io.github.ismoyuan.opspilot.domain.execution;

/**
 * ActionExecution 状态（01 §26、04 §45）。结果无法确定时不新增 UNKNOWN，而是 FAILED + EXECUTION_RESULT_UNCERTAIN（04 §82）；
 * 只有 PENDING → RUNNING 条件更新成功者可以发出一次 CHANGE。
 */
public enum ActionExecutionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
