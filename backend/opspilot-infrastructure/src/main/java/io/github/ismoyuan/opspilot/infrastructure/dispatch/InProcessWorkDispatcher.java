package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.dispatch.ActionExecutionWorker;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.InvestigationWorker;
import io.github.ismoyuan.opspilot.application.dispatch.RecoveryVerificationWorker;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.infrastructure.dispatch.SingleFlightRegistry.OwnerToken;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内派发（07 §45～§48、§51）：在有界线程池上运行对应 Worker，同一工作身份先经 {@link SingleFlightRegistry} 取得拥有权。
 * 线程池拒绝只记录并释放拥有权，数据库中的已提交工作由启动扫描与周期补派发重新唤醒；Worker 结束（含异常）时释放拥有权，
 * 若期间有新 run 唤醒被延后则立即派发。不是持久队列，不做 MQ/Outbox/Lease（08 TASK-035）。
 */
public class InProcessWorkDispatcher implements WorkDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InProcessWorkDispatcher.class);

    private final ExecutorService executor;
    private final SingleFlightRegistry registry;
    private final InvestigationWorker investigations;
    private final ActionExecutionWorker executions;
    private final RecoveryVerificationWorker verifications;

    public InProcessWorkDispatcher(
            ExecutorService executor,
            SingleFlightRegistry registry,
            InvestigationWorker investigations,
            ActionExecutionWorker executions,
            RecoveryVerificationWorker verifications) {
        this.executor = executor;
        this.registry = registry;
        this.investigations = investigations;
        this.executions = executions;
        this.verifications = verifications;
    }

    @Override
    public void dispatch(DispatchableWork work) {
        Optional<OwnerToken> token = registry.acquireOrDefer(work);
        if (token.isEmpty()) {
            log.debug("Dispatch coalesced with running worker: work={}", work);
            return;
        }
        try {
            executor.execute(() -> run(work, token.get()));
        } catch (RejectedExecutionException ex) {
            // 数据库事实仍在：周期补派发会再次唤醒；延后的唤醒同样由扫描覆盖
            registry.release(token.get());
            log.warn("Dispatch rejected, awaiting rescan: work={}", work);
        }
    }

    private void run(DispatchableWork work, OwnerToken token) {
        try (Correlation.Scope ignored = Correlation.open(Correlation.newId())) {
            switch (work) {
                case DispatchableWork.Investigation w -> investigations.runInvestigation(w.incidentId(), w.runNo());
                case DispatchableWork.ActionExecution w -> executions.runActionExecution(w.executionId());
                case DispatchableWork.RecoveryVerification w ->
                    verifications.runRecoveryVerification(w.verificationId());
            }
        } catch (RuntimeException ex) {
            // Worker 自己负责把失败写成数据库事实；这里只保证拥有权释放与后续可再唤醒
            log.warn("Worker failed: work={} exception={}", work, ex.getClass().getName());
        } finally {
            registry.release(token).ifPresent(this::dispatch);
        }
    }

    /** 停止接收新唤醒并等待在途 Worker；未完成的工作由下次启动恢复。 */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
