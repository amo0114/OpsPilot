package io.github.ismoyuan.opspilot.application.dispatch;

/**
 * 进程内唤醒端口（07 §45）；只在业务事务提交后调用，不是持久队列。已提交的工作事实由启动扫描与补派发保证可达（07 §51）。
 * 所有触发——afterCommit、启动恢复、周期补派发——都进入同一受控入口 {@link #dispatch}。唤醒可能被合并、延后或因线程池已满
 * 而放弃，调用方不得据此推断工作是否完成。
 */
public interface WorkDispatcher {

    void dispatch(DispatchableWork work);

    /** 唤醒该 Incident 指定 run 的调查；Worker 必须以此 runNo 做准入校验。 */
    default void dispatchInvestigation(long incidentId, int runNo) {
        dispatch(new DispatchableWork.Investigation(incidentId, runNo));
    }

    default void dispatchActionExecution(long executionId) {
        dispatch(new DispatchableWork.ActionExecution(executionId));
    }

    default void dispatchRecoveryVerification(long verificationId) {
        dispatch(new DispatchableWork.RecoveryVerification(verificationId));
    }
}
