package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.ActionExecutionWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文中没有真实执行 Worker（{@code ActionExecutionService}，08 TASK-071）时的替身，仅用于只装配基础设施的切片测试；正式应用总有
 * 真实 Worker。不改变任何状态、不发出 CHANGE，Execution 保持 PENDING。
 */
class UnwiredActionExecutionWorker implements ActionExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(UnwiredActionExecutionWorker.class);

    @Override
    public void runActionExecution(long executionId) {
        log.debug("No action execution worker in this context; execution stays PENDING: {}", executionId);
    }
}
