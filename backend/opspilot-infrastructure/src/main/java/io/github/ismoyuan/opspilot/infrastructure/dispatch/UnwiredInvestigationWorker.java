package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.InvestigationWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 仅在上下文中没有调查 Worker 时使用（例如 infrastructure 模块单独测试时未装配 application 层）：只记录唤醒，不推进调查、不改任何状态。
 * 完整应用由 InvestigationOrchestrator 提供 Worker（boot 测试断言），不会走到这里。
 */
class UnwiredInvestigationWorker implements InvestigationWorker {

    private static final Logger log = LoggerFactory.getLogger(UnwiredInvestigationWorker.class);

    @Override
    public void runInvestigation(long incidentId, int runNo) {
        log.warn("No investigation worker wired: incidentId={} runNo={}", incidentId, runNo);
    }
}
