package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.dispatch.ActionExecutionWorker;
import io.github.ismoyuan.opspilot.application.dispatch.InvestigationWorker;
import io.github.ismoyuan.opspilot.application.dispatch.RecoveryVerificationWorker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 08 TASK-035～036、07 §45～§51：受控线程上运行、同工作合并、新 run 延后不丢、线程池拒绝不抛给调用方且可再次唤醒、
 * Worker 异常后释放拥有权。线程池为生产同款（固定大小、有界排队、满则拒绝）。
 */
class InProcessWorkDispatcherTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final RecordingWorker worker = new RecordingWorker();
    private InProcessWorkDispatcher dispatcher;

    /** 记录调用并按需阻塞，模拟正在运行的 Worker。 */
    static final class RecordingWorker
            implements InvestigationWorker, ActionExecutionWorker, RecoveryVerificationWorker {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<String> correlations = new CopyOnWriteArrayList<>();
        volatile CountDownLatch release = new CountDownLatch(0);
        volatile CountDownLatch started = new CountDownLatch(1);
        volatile boolean fail;

        @Override
        public void runInvestigation(long incidentId, int runNo) {
            record("investigation:" + incidentId + ":" + runNo);
        }

        @Override
        public void runActionExecution(long executionId) {
            record("execution:" + executionId);
        }

        @Override
        public void runRecoveryVerification(long verificationId) {
            record("verification:" + verificationId);
        }

        private void record(String call) {
            calls.add(call);
            threads.add(Thread.currentThread().getName());
            correlations.add(String.valueOf(Correlation.currentId()));
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (fail) {
                throw new IllegalStateException("worker failed");
            }
        }
    }

    @AfterEach
    void stop() {
        worker.release.countDown();
        dispatcher.shutdown();
    }

    private InProcessWorkDispatcher dispatcher(int maxConcurrency, int queueCapacity) {
        dispatcher = new InProcessWorkDispatcher(
                DispatchConfiguration.workerPool(new WorkerProperties(maxConcurrency, queueCapacity)),
                new SingleFlightRegistry(),
                worker,
                worker,
                worker);
        return dispatcher;
    }

    @Test
    void workRunsOnAWorkerThreadWithItsOwnCorrelationId() throws Exception {
        dispatcher(2, 4).dispatchInvestigation(7, 1);
        dispatcher.dispatchActionExecution(8);
        dispatcher.dispatchRecoveryVerification(9);

        awaitCalls(3);
        assertThat(worker.calls).containsExactlyInAnyOrder("investigation:7:1", "execution:8", "verification:9");
        assertThat(worker.threads).allMatch(name -> name.startsWith("opspilot-worker-"));
        assertThat(worker.correlations).allMatch(id -> id.startsWith("corr_"));
    }

    /** 同一 run 的重复唤醒（afterCommit＋扫描）合并为一次运行；新 run 延后到当前 Worker 结束后运行（07 §48）。 */
    @Test
    void duplicatesCoalesceAndANewRunRunsAfterTheCurrentWorker() throws Exception {
        worker.release = new CountDownLatch(1);
        dispatcher(2, 4).dispatchInvestigation(7, 1);
        assertThat(worker.started.await(5, TimeUnit.SECONDS)).isTrue();

        dispatcher.dispatchInvestigation(7, 1);
        dispatcher.dispatchInvestigation(7, 1);
        dispatcher.dispatchInvestigation(7, 2);
        Thread.sleep(200);
        assertThat(worker.calls).containsExactly("investigation:7:1");

        worker.release.countDown();
        awaitCalls(2);
        Thread.sleep(200);
        assertThat(worker.calls).containsExactly("investigation:7:1", "investigation:7:2");
    }

    /** 线程池满时拒绝只记录、不抛给调用方；拒绝后未被登记，稍后再次唤醒可以运行（08 TASK-035）。 */
    @Test
    void rejectedWakeCanBeDispatchedAgainLater() throws Exception {
        worker.release = new CountDownLatch(1);
        dispatcher(1, 0).dispatchInvestigation(1, 1);
        assertThat(worker.started.await(5, TimeUnit.SECONDS)).isTrue();

        dispatcher.dispatchInvestigation(2, 1);
        Thread.sleep(200);
        assertThat(worker.calls).containsExactly("investigation:1:1");

        worker.release.countDown();
        redispatchUntilCalls(() -> dispatcher.dispatchInvestigation(2, 1), 2);
        assertThat(worker.calls).containsExactly("investigation:1:1", "investigation:2:1");
    }

    @Test
    void failingWorkerReleasesOwnershipForTheNextWake() throws Exception {
        worker.fail = true;
        dispatcher(1, 4).dispatchInvestigation(3, 1);
        awaitCalls(1);

        worker.fail = false;
        redispatchUntilCalls(() -> dispatcher.dispatchInvestigation(3, 1), 2);
        assertThat(worker.calls).containsExactly("investigation:3:1", "investigation:3:1");
    }

    private void awaitCalls(int count) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (worker.calls.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(worker.calls).hasSizeGreaterThanOrEqualTo(count);
    }

    /**
     * 像周期补派发一样反复唤醒同一工作：拥有权释放前的唤醒被合并丢弃，释放后的唤醒真正运行，不依赖固定等待时长。
     */
    private void redispatchUntilCalls(Runnable wake, int count) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (worker.calls.size() < count && System.nanoTime() < deadline) {
            wake.run();
            Thread.sleep(20);
        }
        assertThat(worker.calls).hasSize(count);
    }
}
