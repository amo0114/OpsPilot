package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.ActionExecutionWorker;

/** ActionExecution 表与 Worker 建立前的占位（TASK-068/071 替换并删除本类）；当前没有任何来源会派发执行工作。 */
class PlaceholderActionExecutionWorker implements ActionExecutionWorker {

    @Override
    public void runActionExecution(long executionId) {
        throw new IllegalStateException("Action execution worker not available before TASK-071: " + executionId);
    }
}
