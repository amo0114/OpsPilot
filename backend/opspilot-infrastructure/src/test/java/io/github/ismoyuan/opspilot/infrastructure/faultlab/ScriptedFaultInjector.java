package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.FaultInjector;
import io.github.ismoyuan.opspilot.application.faultlab.FaultTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 测试用注入器：按用例设定成功、失败或阻塞，并记录每次调用时是否处在数据库事务中。只用于测试 Fault Lab 的控制流；真实注入器属
 * TASK-093～095。
 */
final class ScriptedFaultInjector implements FaultInjector {

    private final String scenarioKey;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<Boolean> transactionActive = new CopyOnWriteArrayList<>();
    volatile RuntimeException injectFailure;
    volatile RuntimeException verifyFailure;
    volatile RuntimeException resetFailure;
    /** 故障生效时间相对当前时间的偏移。 */
    volatile Duration startedAgo = Duration.ofSeconds(5);
    /** 确认时间相对当前时间的偏移；大于 startedAgo 时确认时间早于生效时间（违反 started_at ≤ detected_at）。 */
    volatile Duration detectedAgo = Duration.ofSeconds(1);

    /** 确认时先执行的动作（模拟确认期间环境变化）。 */
    volatile Runnable onVerify;

    volatile CountDownLatch injectEntered;
    volatile CountDownLatch releaseInject;

    ScriptedFaultInjector(String scenarioKey) {
        this.scenarioKey = scenarioKey;
    }

    void clear() {
        calls.clear();
        transactionActive.clear();
        injectFailure = null;
        verifyFailure = null;
        resetFailure = null;
        startedAgo = Duration.ofSeconds(5);
        detectedAgo = Duration.ofSeconds(1);
        injectEntered = null;
        releaseInject = null;
        onVerify = null;
    }

    @Override
    public String scenarioKey() {
        return scenarioKey;
    }

    @Override
    public Instant inject(FaultTarget target) {
        record("inject", target);
        CountDownLatch entered = injectEntered;
        if (entered != null) {
            entered.countDown();
            await(releaseInject);
        }
        if (injectFailure != null) {
            throw injectFailure;
        }
        return Instant.now().minus(startedAgo);
    }

    @Override
    public Instant verifyInjected(FaultTarget target) {
        record("verify", target);
        Runnable action = onVerify;
        if (action != null) {
            action.run();
        }
        if (verifyFailure != null) {
            throw verifyFailure;
        }
        return Instant.now().minus(detectedAgo);
    }

    @Override
    public void reset(FaultTarget target) {
        record("reset", target);
        if (resetFailure != null) {
            throw resetFailure;
        }
    }

    private void record(String call, FaultTarget target) {
        calls.add(call + ":" + target.scenarioKey() + "@" + target.systemKey() + "/" + target.targetResourceKey());
        transactionActive.add(
                org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (latch != null && !latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("inject was not released");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
