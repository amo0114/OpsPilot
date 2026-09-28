package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.InvestigationWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调查循环实现前的占位（TASK-037～043 以 InvestigationOrchestrator 替换并删除本类）：只记录唤醒，不推进调查、不改任何状态。
 * Incident 会停在 INVESTIGATING，由周期补派发反复唤醒，不能视为后台调查已完成。
 */
class PlaceholderInvestigationWorker implements InvestigationWorker {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderInvestigationWorker.class);

    @Override
    public void runInvestigation(long incidentId, int runNo) {
        log.debug("Investigation worker not available before TASK-037: incidentId={} runNo={}", incidentId, runNo);
    }
}
