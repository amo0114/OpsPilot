package io.github.ismoyuan.opspilot.application.dispatch;

/** ActionExecution Worker 入口（07 §64～§67）；真实实现属 TASK-071/073。 */
public interface ActionExecutionWorker {

    void runActionExecution(long executionId);
}
