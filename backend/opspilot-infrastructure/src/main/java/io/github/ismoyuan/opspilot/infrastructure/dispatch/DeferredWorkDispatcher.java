package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 08 TASK-016 允许的初期占位派发器：只记录唤醒，不执行任何调查。
 * 已提交的 INVESTIGATING 仍停留在数据库，不能视为后台流程完成；InProcessWorkDispatcher 与 Worker 在 TASK-035 起替换本类。
 */
@Component
class DeferredWorkDispatcher implements WorkDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeferredWorkDispatcher.class);

    @Override
    public void dispatchInvestigation(long incidentId, int runNo) {
        log.info(
                "Investigation dispatch deferred (no worker before TASK-035): incidentId={} runNo={}",
                incidentId,
                runNo);
    }
}
