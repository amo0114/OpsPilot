package io.github.ismoyuan.opspilot.application.dispatch;

/**
 * ActionExecution Worker 入口（07 §64～§67）：PENDING 经唯一准入发出一次 CHANGE，RUNNING 只做有界只读核对，终态不处理
 * （TASK-071～073）。
 */
public interface ActionExecutionWorker {

    void runActionExecution(long executionId);
}
