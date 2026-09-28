package io.github.ismoyuan.opspilot.application.dispatch;

/**
 * 调查 Worker 入口（07 §41）：只推进给定 run，由自身 Guard 在事务内核对状态、run、Stop 与预算（TASK-037～043）。
 * 由派发器在受控线程上调用，同一 Incident 同一 JVM 内至多一个（07 §48）。
 */
public interface InvestigationWorker {

    void runInvestigation(long incidentId, int runNo);
}
