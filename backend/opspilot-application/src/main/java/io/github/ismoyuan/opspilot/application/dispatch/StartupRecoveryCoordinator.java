package io.github.ismoyuan.opspilot.application.dispatch;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 启动恢复与存活期间补派发（07 §51）：读取数据库中仍需 Worker 的工作并交给同一派发入口。派发器会合并已由本 JVM Worker
 * 拥有的同一工作，因此补派发不会动到正在运行的工作。两条入口都不刷新 run、预算或 deadline，也不调用 resumeInvestigation。
 *
 * <p>只有 {@link #recoverAfterStartup} 可以认定旧进程遗留的 RUNNING 记录已中断；这部分处理由 TASK-043（调查）、TASK-073
 * （Execution）、TASK-083（Verification）加入启动路径，周期补派发永远不做中断标记。
 */
@Service
public class StartupRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryCoordinator.class);

    private final List<DispatchableWorkSource> sources;
    private final WorkDispatcher dispatcher;

    public StartupRecoveryCoordinator(List<DispatchableWorkSource> sources, WorkDispatcher dispatcher) {
        this.sources = List.copyOf(sources);
        this.dispatcher = dispatcher;
    }

    /** 应用就绪后调用一次；单实例部署，此时数据库中的 RUNNING 都属于已退出的旧进程（07 §50～§51）。 */
    public int recoverAfterStartup() {
        return dispatchAll("startup");
    }

    /** 周期补派发：唤醒没有 Worker 的已提交工作，例如线程池拒绝或 afterCommit 之后崩溃前未唤醒的工作。 */
    public int redispatchPending() {
        return dispatchAll("rescan");
    }

    /** @return 交给派发器的工作数（可能被合并或延后） */
    private int dispatchAll(String trigger) {
        int dispatched = 0;
        for (DispatchableWorkSource source : sources) {
            List<? extends DispatchableWork> works;
            try {
                works = source.findDispatchable();
            } catch (RuntimeException ex) {
                log.warn(
                        "Dispatchable work scan failed: trigger={} source={} exception={}",
                        trigger,
                        source.getClass().getSimpleName(),
                        ex.getClass().getName());
                continue;
            }
            for (DispatchableWork work : works) {
                dispatcher.dispatch(work);
                dispatched++;
            }
        }
        return dispatched;
    }
}
