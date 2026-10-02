package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopSettings;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjection;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.application.faultlab.FaultTarget;
import io.github.ismoyuan.opspilot.application.faultlab.StatisticsConsumerStopInjector;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * S3 注入器的 Preflight、Baseline、Gate 与 Reset 判定（08 TASK-093、09 §15～§16、§61、§65），用脚本化的靶场替身；真实 Docker/Redis 的
 * 行为见 {@link ConsumerStopInjectorIntegrationTest}。每个失败用例同时断言是否真的停止了消费者。
 */
class StatisticsConsumerStopInjectorTest {

    static final String CONTAINER = "ab".repeat(32);
    static final FaultTarget TARGET =
            new FaultTarget(7, "statistics-consumer-stop", "shortlink-platform", 3, "statistics-consumer");

    /** 毫秒级的短窗口：Baseline 约 40ms、采样 5ms、Gate 最长 400ms。 */
    static final ConsumerStopSettings FAST = new ConsumerStopSettings(
            Duration.ofMillis(40),
            Duration.ofMillis(5),
            2,
            5.0,
            20,
            20,
            Duration.ofSeconds(10),
            Duration.ofMillis(400),
            50,
            0.01,
            4.0,
            Duration.ofMillis(500),
            Duration.ofMillis(300));

    private final Fake environment = new Fake();
    private final StatisticsConsumerStopInjector injector = new StatisticsConsumerStopInjector(
            environment, FAST, "shortlink-platform", "statistics-consumer", Clock.systemUTC());

    @Test
    void aStoppedConsumerWithGrowingLagIsConfirmed() {
        Instant before = Instant.now();
        FaultInjection injection = injector.inject(TARGET);
        Instant detected = injector.verifyInjected(TARGET);

        assertThat(environment.calls).containsExactly("stop:" + CONTAINER + ":PT10S");
        assertThat(injection.stoppedContainerId()).isEqualTo(CONTAINER);
        assertThat(injection.startedAt()).isEqualTo(environment.finishedAt).isAfterOrEqualTo(before);
        assertThat(detected).isAfterOrEqualTo(injection.startedAt()).isBeforeOrEqualTo(Instant.now());
        // 至少三次 Gate 采样，且末次 lag 较基线增长 ≥ 50
        assertThat(environment.gateReads).isGreaterThanOrEqualTo(3);
        assertThat(environment.lag).isGreaterThanOrEqualTo(environment.baselineLag + 50);
        // 确认时刻不晚于整体期限
        assertThat(detected).isBeforeOrEqualTo(injection.startedAt().plus(FAST.gateTimeout()));
    }

    /**
     * B34-R1 P1：停止后外部负载退出，跳转探测仍在写入统计消息（ID 前进、lag 上升），Gate 扣除探测后写入速率不足，不能确认，消费者保持
     * 停止。
     */
    @Test
    void probeTrafficAloneDoesNotPassTheGateAfterTheLoadStops() {
        injector.inject(TARGET);
        environment.producing = false;

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageStartingWith("Gate failed: not met within")
                .hasMessageContaining("statistics messages are produced below the expected load rate");
        // 探测确实写入并推高了 lag：旧判定会在这里通过
        assertThat(environment.lag).isGreaterThanOrEqualTo(environment.baselineLag + 50);
        assertThat(environment.running).isFalse();
    }

    /** B34-R1 P2：期限之后才完成的采样不被接受，即使它满足全部条件。 */
    @Test
    void aSampleCompletedAfterTheDeadlineIsNotAccepted() {
        injector.inject(TARGET);
        // 第三次 Gate 读取（首次可能满足全部条件）返回时已超过 400ms 期限
        environment.delayedGateRead = 3;
        environment.readDelay = Duration.ofMillis(450);

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessage("Gate failed: not met within 0 seconds (confirmed only after the deadline)");
        assertThat(environment.gateReads).isEqualTo(3);
    }

    /** 期限之后不再开始新的采样或调用（等待裁剪到剩余时间）。 */
    @Test
    void noSampleStartsAfterTheDeadline() {
        injector.inject(TARGET);
        environment.lagGrowthPerRead = 0;
        Instant started = Instant.now();

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageContaining("consumer group lag is not rising");
        assertThat(environment.lastGateReadAt).isBefore(environment.finishedAt.plus(FAST.gateTimeout()));
        assertThat(Duration.between(started, Instant.now()))
                .isLessThan(FAST.gateTimeout().plusMillis(200));
    }

    /** B34-R1 P1：只控制绑定的系统与目标；其他系统的 inject/verify/reset 在任何外部动作之前拒绝。 */
    @Test
    void otherSystemsAndResourcesAreNotControlled() {
        FaultTarget other = new FaultTarget(8, "statistics-consumer-stop", "test-platform", 9, "statistics-consumer");
        FaultTarget otherResource =
                new FaultTarget(9, "statistics-consumer-stop", "shortlink-platform", 4, "shortlink-redis");

        assertThat(injector.controls("shortlink-platform", "statistics-consumer"))
                .isTrue();
        assertThat(injector.controls("test-platform", "statistics-consumer")).isFalse();
        assertThat(injector.controls("shortlink-platform", "shortlink-redis")).isFalse();
        for (FaultTarget target : List.of(other, otherResource)) {
            assertThatThrownBy(() -> injector.inject(target))
                    .hasMessage("Fault lab control is not bound to the requested system");
            assertThatThrownBy(() -> injector.verifyInjected(target))
                    .hasMessage("Fault lab control is not bound to the requested system");
            assertThatThrownBy(() -> injector.reset(target))
                    .hasMessage("Fault lab control is not bound to the requested system");
        }
        assertThat(environment.calls).isEmpty();
        assertThat(environment.inspects).isZero();
    }

    @Test
    void preflightRefusesAnUnhealthyEnvironmentWithoutStopping() {
        environment.health = ConsumerStopEnvironment.HealthState.STARTING;
        assertPreflightFails("statistics consumer is not running and healthy");

        environment.health = ConsumerStopEnvironment.HealthState.HEALTHY;
        environment.reportLag = false;
        assertPreflightFails("Redis did not report the consumer group lag");

        environment.reportLag = true;
        environment.idleLag = 21;
        assertPreflightFails("lag is above the healthy threshold");

        environment.idleLag = 0;
        environment.pending = 21;
        assertPreflightFails("pending is above the healthy threshold");

        environment.pending = 0;
        environment.producing = false;
        assertPreflightFails("statistics messages are produced below the expected load rate");

        // ID 仍在前进，但新增的只有跳转探测自身写入的消息（扣除后为 0）
        environment.producing = true;
        environment.entriesPerRead = 0;
        assertPreflightFails("statistics messages are produced below the expected load rate");

        // Redis 不提供累计写入数时无法证明负载
        environment.entriesPerRead = 10;
        environment.reportEntriesAdded = false;
        assertPreflightFails("statistics messages are produced below the expected load rate");

        environment.reportEntriesAdded = true;
        environment.probeSucceeds = false;
        assertPreflightFails("redirect requests are failing");
    }

    @Test
    void aConsumerThatDoesNotStopFailsTheInjection() {
        environment.ignoreStop = true;

        assertThatThrownBy(() -> injector.inject(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessage("Statistics consumer was not stopped by the injection");
    }

    @Test
    void lagThatDoesNotGrowEnoughTimesOutAndLeavesTheConsumerStopped() {
        // Gate 100ms、采样 5ms：最多约 20 次读取，每次 +1 不可能达到 +50
        StatisticsConsumerStopInjector shortGate = new StatisticsConsumerStopInjector(
                environment,
                new ConsumerStopSettings(
                        FAST.baselineDuration(),
                        FAST.sampleInterval(),
                        FAST.probesPerSample(),
                        FAST.minLoadMessageRate(),
                        FAST.healthyLag(),
                        FAST.healthyPending(),
                        FAST.stopGrace(),
                        Duration.ofMillis(100),
                        FAST.minLagGrowth(),
                        FAST.maxErrorRate(),
                        FAST.p99Factor(),
                        FAST.p99Floor(),
                        FAST.resetTimeout()),
                "shortlink-platform",
                "statistics-consumer",
                Clock.systemUTC());
        environment.lagGrowthPerRead = 1;
        shortGate.inject(TARGET);

        assertThatThrownBy(() -> shortGate.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageStartingWith("Gate failed: not met within")
                .hasMessageContaining("lag growth is below the threshold");
        assertThat(environment.running).isFalse();
        assertThat(environment.calls).noneMatch(call -> call.startsWith("start"));
    }

    @Test
    void lagThatStopsRisingIsNotConfirmed() {
        environment.lagGrowthPerRead = 0;
        injector.inject(TARGET);

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageContaining("consumer group lag is not rising");
    }

    @Test
    void aProducerThatStopsIsNotConfirmed() {
        injector.inject(TARGET);
        // 没有任何写入（负载与探测都不写）：最近生成 ID 不再前进
        environment.producing = false;
        environment.probeWrites = false;

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageContaining("producer is not generating new messages");
    }

    @Test
    void redirectFailuresAfterTheStopFailTheGate() {
        injector.inject(TARGET);
        environment.probeSucceeds = false;

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageContaining("redirect error rate is at or above the limit");
    }

    @Test
    void aSeverelySlowerRedirectFailsTheGate() {
        injector.inject(TARGET);
        // 基线 2ms → 上限 max(8ms, 500ms)；600ms 超过
        environment.probeLatency = Duration.ofMillis(600);

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageContaining("redirect P99 is severely degraded");
    }

    @Test
    void aConsumerRestartedBeforeTheGateFailsImmediately() {
        injector.inject(TARGET);
        environment.running = true;

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessage("Gate failed: statistics consumer is no longer stopped");
        assertThat(environment.gateReads).isZero();
    }

    @Test
    void verifyingAnUnknownInjectionFails() {
        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessage("Gate failed: the injection of this experiment is unknown");
    }

    @Test
    void resetStartsAStoppedConsumerAndWaitsUntilItIsHealthy() {
        injector.inject(TARGET);
        environment.unhealthyInspectsAfterStart = 3;

        injector.reset(TARGET);

        assertThat(environment.calls).containsExactly("stop:" + CONTAINER + ":PT10S", "start:" + CONTAINER);
        assertThat(environment.running).isTrue();
    }

    @Test
    void resetLeavesARunningConsumerAlone() {
        injector.reset(TARGET);

        assertThat(environment.calls).isEmpty();
    }

    @Test
    void resetFailsWhenTheConsumerNeverBecomesHealthy() {
        injector.inject(TARGET);
        environment.unhealthyInspectsAfterStart = Integer.MAX_VALUE;

        assertThatThrownBy(() -> injector.reset(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessage("Reset failed: statistics consumer did not become healthy");
    }

    @Test
    void resetRefusesAPausedConsumer() {
        environment.paused = true;

        assertThatThrownBy(() -> injector.reset(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessage("Reset failed: statistics consumer is paused");
    }

    private void assertPreflightFails(String message) {
        assertThatThrownBy(() -> injector.inject(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageStartingWith("Preflight failed")
                .hasMessageContaining(message);
        assertThat(environment.calls).isEmpty();
        assertThat(environment.running).isTrue();
    }

    /** 脚本化的靶场：运行时 lag 保持 idleLag、生产者每次读取前进一格；停止后每次读取 lag 增加 lagGrowthPerRead。 */
    static final class Fake implements ConsumerStopEnvironment {

        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile boolean running = true;
        volatile boolean paused;
        volatile boolean ignoreStop;
        volatile HealthState health = HealthState.HEALTHY;
        volatile int unhealthyInspectsAfterStart;
        volatile Instant finishedAt;
        volatile boolean reportLag = true;
        volatile long idleLag = 2;
        volatile long pending = 3;
        volatile boolean producing = true;
        volatile long entriesPerRead = 10;
        /** 跳转探测是否像 ShortLink 一样写入一条统计消息。 */
        volatile boolean probeWrites = true;

        volatile int delayedGateRead = -1;
        volatile Duration readDelay = Duration.ZERO;
        volatile Instant lastGateReadAt;
        volatile int inspects;
        volatile boolean reportEntriesAdded = true;
        volatile long lagGrowthPerRead = 30;
        volatile long lag;
        volatile long baselineLag;
        volatile int gateReads;
        volatile boolean probeSucceeds = true;
        volatile Duration probeLatency = Duration.ofMillis(2);
        private long generated = 1_000;
        private long entriesAdded = 5_000;

        @Override
        public ConsumerContainer inspectConsumer(Instant deadline) {
            inspects++;
            if (paused) {
                return new ConsumerContainer(CONTAINER, RuntimeState.PAUSED, health, Instant.EPOCH, null);
            }
            if (!running) {
                return new ConsumerContainer(CONTAINER, RuntimeState.STOPPED, health, Instant.EPOCH, finishedAt);
            }
            HealthState current = health;
            if (unhealthyInspectsAfterStart > 0) {
                unhealthyInspectsAfterStart--;
                current = HealthState.STARTING;
            }
            return new ConsumerContainer(CONTAINER, RuntimeState.RUNNING, current, Instant.EPOCH, finishedAt);
        }

        @Override
        public void stopConsumer(String containerId, Duration grace, Instant deadline) {
            calls.add("stop:" + containerId + ":" + grace);
            if (!ignoreStop) {
                running = false;
                finishedAt = Instant.now();
                baselineLag = lag;
            }
        }

        @Override
        public void startConsumer(String containerId, Instant deadline) {
            calls.add("start:" + containerId);
            running = true;
        }

        @Override
        public synchronized StreamSnapshot readStream(Instant deadline) {
            if (producing) {
                generated++;
                entriesAdded += entriesPerRead;
            }
            if (running) {
                lag = idleLag;
            } else {
                lag += lagGrowthPerRead;
                gateReads++;
                lastGateReadAt = Instant.now();
                if (gateReads == delayedGateRead) {
                    try {
                        Thread.sleep(readDelay);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return new StreamSnapshot(
                    Instant.now(),
                    generated,
                    0,
                    reportEntriesAdded ? entriesAdded : null,
                    reportLag ? lag : null,
                    pending);
        }

        @Override
        public synchronized RedirectProbe probeRedirect(Instant deadline) {
            if (probeWrites) {
                generated++;
                entriesAdded++;
            }
            return new RedirectProbe(probeSucceeds, probeLatency);
        }
    }
}
