package io.github.ismoyuan.opspilot.application.dispatch;

/**
 * 进程内唤醒端口（07 §45）；只在业务事务提交后调用，不是持久队列。已提交的工作事实由启动扫描与补派发保证可达（07 §51）。
 * Execution 与 Verification 的派发方法随各自 Task 加入。
 */
public interface WorkDispatcher {

    /** 唤醒该 Incident 指定 run 的调查；Worker 必须以此 runNo 做准入校验。 */
    void dispatchInvestigation(long incidentId, int runNo);
}
