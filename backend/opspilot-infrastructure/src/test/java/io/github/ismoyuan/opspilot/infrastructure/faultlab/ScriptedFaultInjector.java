package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.FaultInjection;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjector;
import io.github.ismoyuan.opspilot.application.faultlab.FaultTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 测试用注入器：按用例设定成功、失败或阻塞，并记录每次调用时是否处在数据库事务中。只用于测试 Fault Lab 的控制流；真实注入器见
 * StatisticsConsumerStopInjector（TASK-093）及 TASK-094～095。
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
    /** 不控制的系统（controls 返回 false）。 */
    volatile Set<String> uncontrolledSystems = Set.of();
    /** 注入报告的被停止容器（写入 Ground Truth）；为空时不报告。 */
    volatile String stoppedContainerId;

    /** 确认时先执行的动作（模拟确认期间环境变化）。 */
    volatile Runnable onVerify;

    volatile CountDownLatch resetEntered;
    volatile CountDownLatch releaseReset;
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
        stoppedContainerId = null;
        uncontrolledSystems = Set.of();
        resetEntered = null;
        releaseReset = null;
        injectEntered = null;
        releaseInject = null;
        onVerify = null;
    }

    @Override
    public String scenarioKey() {
        return scenarioKey;
    }

    @Override
    public boolean controls(String systemKey, String targetResourceKey) {
        return !uncontrolledSystems.contains(systemKey);
    }

    @Override
    public FaultInjection inject(FaultTarget target) {
        record("inject", target);
        CountDownLatch entered = injectEntered;
        if (entered != null) {
            entered.countDown();
            await(releaseInject);
        }
        if (injectFailure != null) {
            throw injectFailure;
        }
        return new FaultInjection(Instant.now().minus(startedAgo), stoppedContainerId);
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
        CountDownLatch entered = resetEntered;
        if (entered != null) {
            entered.countDown();
            await(releaseReset);
        }
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
