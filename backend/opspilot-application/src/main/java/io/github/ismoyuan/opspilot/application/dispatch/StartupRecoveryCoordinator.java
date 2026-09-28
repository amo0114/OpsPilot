package io.github.ismoyuan.opspilot.application.dispatch;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 启动恢复与存活期间补派发（07 §51）：读取数据库中仍需 Worker 的工作并交给同一派发入口。派发器会合并已由本 JVM Worker
 * 拥有的同一工作，因此补派发不会动到正在运行的工作。两条入口都不刷新 run、预算或 deadline，也不调用 resumeInvestigation。
 *
 * <p>派发前先由各 {@link InterruptedWorkRecorder} 把旧进程遗留的 RUNNING 记录标为中断（调查 TASK-043；Execution、Verification
 * 由 TASK-073/083 加入）。中断只针对本进程启动之前开始的记录（构造时刻为界），因此绝不会标记本 JVM Worker 仍拥有的 RUNNING；
 * 这一步全部成功之前不派发任何工作（07 §52：先终结旧进程 RUNNING，再判断并派发）；启动时失败则由之后的补派发以同一界限重试，
 * 成功后才开始派发，此后补派发不再标记（B12-R1）。
 */
@Service
public class StartupRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryCoordinator.class);

    private final List<DispatchableWorkSource> sources;
    private final List<InterruptedWorkRecorder> interruptions;
    private final WorkDispatcher dispatcher;
    private final Instant processStartedAt;
    private volatile boolean interruptionsRecorded;

    public StartupRecoveryCoordinator(
            List<DispatchableWorkSource> sources,
            List<InterruptedWorkRecorder> interruptions,
            WorkDispatcher dispatcher,
            Clock clock) {
        this.sources = List.copyOf(sources);
        this.interruptions = List.copyOf(interruptions);
        this.dispatcher = dispatcher;
        // 本进程的运行记录都在此之后开始（started_at 同样截断到毫秒），严格早于它的 RUNNING 只可能属于旧进程
        this.processStartedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * 应用就绪后调用一次；单实例部署，此时数据库中的 RUNNING 都属于已退出的旧进程（07 §50～§51）。
     *
     * @return 交给派发器的工作数；中断记录未成功时为 0，派发推迟到补派发
     */
    public int recoverAfterStartup() {
        if (!recordInterruptions()) {
            return 0;
        }
        return dispatchAll("startup");
    }

    /** 周期补派发：唤醒没有 Worker 的已提交工作，例如线程池拒绝或 afterCommit 之后崩溃前未唤醒的工作。 */
    public int redispatchPending() {
        if (!recordInterruptions()) {
            return 0;
        }
        return dispatchAll("rescan");
    }

    /**
     * 以启动时刻为界记录旧进程中断，成功一次后不再执行。任一 recorder 失败即整体未完成：本次不派发，下次补派发全部重试
     * （只影响仍为 RUNNING 的记录，重复执行安全）。
     *
     * @return 旧进程中断是否已全部记录
     */
    private synchronized boolean recordInterruptions() {
        if (interruptionsRecorded) {
            return true;
        }
        boolean recorded = true;
        for (InterruptedWorkRecorder recorder : interruptions) {
            try {
                recorder.recordInterrupted(processStartedAt);
            } catch (RuntimeException ex) {
                recorded = false;
                log.warn(
                        "Interrupted work recording failed: recorder={} exception={}",
                        recorder.getClass().getSimpleName(),
                        ex.getClass().getName());
            }
        }
        if (!recorded) {
            log.warn("Recovery dispatch postponed until interrupted work is recorded");
        }
        interruptionsRecorded = recorded;
        return recorded;
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
