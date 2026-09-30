package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.ActionExecutionWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实执行 Worker（TASK-071）建立前的占位，TASK-071 替换并删除本类。批准事务（TASK-069）提交后会派发 PENDING Execution，周期补派发也会
 * 再次唤醒它；占位不改变任何状态——Execution 保持 PENDING，不发出 CHANGE——只记 debug 日志，避免补派发每轮产生告警。
 */
class PlaceholderActionExecutionWorker implements ActionExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderActionExecutionWorker.class);

    @Override
    public void runActionExecution(long executionId) {
        log.debug("Action execution worker not available before TASK-071; execution stays PENDING: {}", executionId);
    }
}
