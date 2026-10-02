package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RedirectProbe;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.StreamSnapshot;
import io.github.ismoyuan.opspilot.application.faultlab.FaultConfirmation;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1.SymptomBranch;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjection;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.application.faultlab.FaultTarget;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyEnvironment;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyInjector;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencySettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * S1 注入器的 Preflight、Baseline、Gate 分支与 Reset（08 TASK-094、09 §15～§16、§30～§33、§41），用脚本化的靶场替身；真实 Toxiproxy/Redis
 * 见 {@link RedisLatencyInjectorIntegrationTest}。
 */
class RedisLatencyInjectorTest {

    static final FaultTarget TARGET = new FaultTarget(21, "redis-latency", "shortlink-platform", 5, "shortlink-redis");

    /** 毫秒级窗口：Baseline 40ms、采样 5ms、Gate 400ms、Reset 300ms；PING 门槛 50ms/10ms，P99 下限 80ms，Reset 跳转 P99 上限 20ms。 */
    static final RedisLatencySettings FAST = new RedisLatencySettings(
            600,
            0,
            1.0,
            Duration.ofMillis(40),
            Duration.ofMillis(5),
            2,
            5.0,
            20,
            20,
            0.01,
            5,
            Duration.ofMillis(10),
            Duration.ofMillis(20),
            Duration.ofMillis(50),
            4,
            4.0,
            Duration.ofMillis(80),
            0.05,
            0.05,
            Duration.ofMillis(400),
            Duration.ofMillis(300));

    private final Fake environment = new Fake();
    private final RedisLatencyInjector injector =
            new RedisLatencyInjector(environment, FAST, "shortlink-platform", "shortlink-redis", Clock.systemUTC());

    @Test
    void slowRedirectsConfirmTheLatencyBranch() {
        environment.faultProbeLatency = Duration.ofMillis(120);

        FaultInjection injection = injector.inject(TARGET);
        FaultConfirmation confirmation = injector.verifyInjected(TARGET);

        assertThat(environment.calls).containsExactly("add:" + RedisLatencyInjector.TOXIC_NAME + ":600:0:1.0");
        assertThat(confirmation.detectedAt())
                .isAfterOrEqualTo(injection.startedAt())
                .isBeforeOrEqualTo(injection.startedAt().plus(FAST.gateTimeout()));
        var gate = confirmation.redisLatencyGate();
        assertThat(gate.symptomBranch()).isEqualTo(SymptomBranch.LATENCY);
        assertThat(gate.injectedLatencyMs()).isEqualTo(600);
        assertThat(gate.pingMedianMs()).isEqualTo(60);
        assertThat(gate.baselineP99Ms()).isEqualTo(2);
        assertThat(gate.faultP99Ms()).isEqualTo(120);
        assertThat(gate.baselineErrorRate()).isZero();
        assertThat(gate.faultErrorRate()).isZero();
        assertThat(injection.stoppedContainerId()).isNull();
    }

    @Test
    void failingFastRedirectsConfirmTheErrorRateBranch() {
        environment.faultProbeSucceeds = false;

        injector.inject(TARGET);
        var gate = injector.verifyInjected(TARGET).redisLatencyGate();

        assertThat(gate.symptomBranch()).isEqualTo(SymptomBranch.ERROR_RATE);
        assertThat(gate.faultErrorRate()).isEqualTo(1.0);
        assertThat(gate.faultP99Ms()).isLessThan(80);
    }

    @Test
    void slowAndFailingRedirectsConfirmBoth() {
        environment.faultProbeSucceeds = false;
        environment.faultProbeLatency = Duration.ofMillis(120);

        injector.inject(TARGET);

        assertThat(injector.verifyInjected(TARGET).redisLatencyGate().symptomBranch())
                .isEqualTo(SymptomBranch.BOTH);
    }

    /** 09 §33：Redis 延迟生效但业务无影响属于 SETUP_FAILED；toxic 保留，由 Reset 删除。 */
    @Test
    void slowRedisWithoutABusinessSymptomFailsTheGate() {
        injector.inject(TARGET);

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessage("Gate failed: not met within 0 seconds (redirect symptoms are below both gate branches)");
        assertThat(environment.toxics).containsExactly(RedisLatencyInjector.TOXIC_NAME);
    }

    @Test
    void aToxicWithoutRealLatencyFailsTheGate() {
        environment.faultPing = Duration.ofMillis(5);
        environment.faultProbeLatency = Duration.ofMillis(120);
        injector.inject(TARGET);

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .hasMessageContaining("the Redis round trip through the proxy is below the gate");
    }

    @Test
    void aRemovedToxicFailsTheGateImmediately() {
        injector.inject(TARGET);
        environment.toxics.clear();

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .hasMessage("Gate failed: the latency toxic is no longer present");
    }

    /** 期限之后才完成的采样不被接受，即使它满足全部条件。 */
    @Test
    void aSampleCompletedAfterTheDeadlineIsNotAccepted() {
        environment.faultProbeLatency = Duration.ofMillis(120);
        injector.inject(TARGET);
        // 第 2 次 Gate 采样（累计 4 次探测，首次可能满足全部条件）的 PING 返回时已超过 400ms 期限
        environment.delayedPingCall = environment.pingCalls + 2;
        environment.pingDelay = Duration.ofMillis(450);

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .hasMessage("Gate failed: not met within 0 seconds (confirmed only after the deadline)");
    }

    @Test
    void preflightRefusesAnUnhealthyEnvironmentWithoutInjecting() {
        environment.enabled = false;
        assertPreflightFails("the Redis proxy is disabled");

        environment.enabled = true;
        environment.toxics.add("leftover");
        assertPreflightFails("the Redis proxy already has toxics");

        environment.toxics.clear();
        environment.healthyPing = Duration.ofMillis(20);
        assertPreflightFails("the Redis round trip through the proxy is not healthy");

        environment.healthyPing = Duration.ofMillis(1);
        environment.lag = 21;
        assertPreflightFails("lag is above the healthy threshold");

        environment.lag = 0;
        environment.entriesPerRead = 0;
        assertPreflightFails("produced below the expected load rate");

        environment.entriesPerRead = 10;
        environment.baselineProbeSucceeds = false;
        assertPreflightFails("redirect requests are failing");
    }

    @Test
    void otherSystemsAndResourcesAreNotControlled() {
        FaultTarget other = new FaultTarget(22, "redis-latency", "test-platform", 9, "shortlink-redis");
        FaultTarget otherResource = new FaultTarget(23, "redis-latency", "shortlink-platform", 6, "shortlink-mysql");

        assertThat(injector.controls("shortlink-platform", "shortlink-redis")).isTrue();
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
    void resetRemovesTheToxicAndWaitsUntilRedisAndRedirectsRecover() {
        environment.faultProbeLatency = Duration.ofMillis(120);
        injector.inject(TARGET);
        environment.slowAfterRemoval = 3;

        injector.reset(TARGET);

        assertThat(environment.calls)
                .containsExactly(
                        "add:" + RedisLatencyInjector.TOXIC_NAME + ":600:0:1.0",
                        "remove:" + RedisLatencyInjector.TOXIC_NAME);
        assertThat(environment.toxics).isEmpty();
    }

    /** B35-R1：Redis 已恢复、跳转成功但仍慢（P99 高于健康上限）时 Reset 继续等待，跳转变快后才完成（09 §41）。 */
    @Test
    void resetWaitsUntilRedirectsAreFastAgain() {
        injector.inject(TARGET);
        environment.slowRedirectsAfterRemoval = 6;

        injector.reset(TARGET);

        assertThat(environment.slowRedirectsAfterRemoval).isZero();
        assertThat(environment.toxics).isEmpty();
    }

    @Test
    void resetFailsWhileRedirectsStaySlow() {
        injector.inject(TARGET);
        environment.slowRedirectsAfterRemoval = Integer.MAX_VALUE;

        assertThatThrownBy(() -> injector.reset(TARGET))
                .hasMessage("Reset failed: the Redis round trip or redirects did not recover");
    }

    @Test
    void resetWithoutAToxicOnlyConfirmsRecovery() {
        injector.reset(TARGET);

        assertThat(environment.calls).containsExactly("remove:" + RedisLatencyInjector.TOXIC_NAME);
    }

    @Test
    void resetFailsWhenRedisDoesNotRecover() {
        injector.inject(TARGET);
        environment.slowAfterRemoval = Integer.MAX_VALUE;

        assertThatThrownBy(() -> injector.reset(TARGET))
                .hasMessage("Reset failed: the Redis round trip or redirects did not recover");
    }

    private void assertPreflightFails(String message) {
        assertThatThrownBy(() -> injector.inject(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageStartingWith("Preflight failed")
                .hasMessageContaining(message);
        assertThat(environment.calls).noneMatch(call -> call.startsWith("add"));
    }

    /**
     * 脚本化的靶场：toxic 存在时 PING 为 faultPing、跳转按 fault* 配置；不存在时 PING 为 healthyPing（Reset 后可先慢 slowAfterRemoval 次）、
     * 跳转 2ms 成功；生产者每次读取写入 entriesPerRead 条。
     */
    static final class Fake implements RedisLatencyEnvironment {

        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> toxics = Collections.synchronizedList(new ArrayList<>());
        volatile boolean enabled = true;
        volatile Duration healthyPing = Duration.ofMillis(1);
        volatile Duration faultPing = Duration.ofMillis(60);
        volatile Duration pingDelay = Duration.ZERO;
        /** 只延迟第几次 PING 测量（从 1 计）；未设置时不延迟。 */
        volatile int delayedPingCall = -1;

        volatile int pingCalls;
        volatile int slowAfterRemoval;
        volatile long lag;
        volatile long entriesPerRead = 10;
        volatile boolean baselineProbeSucceeds = true;
        volatile boolean faultProbeSucceeds = true;
        volatile Duration faultProbeLatency = Duration.ofMillis(2);
        /** toxic 删除后仍有多少次跳转成功但慢（60ms，高于 FAST 的健康上限 20ms）。 */
        volatile int slowRedirectsAfterRemoval;

        volatile int inspects;
        private long generated = 1_000;
        private long entries = 5_000;

        @Override
        public ProxyState inspectProxy(Instant deadline) {
            inspects++;
            return new ProxyState(enabled, List.copyOf(toxics));
        }

        @Override
        public void addLatency(LatencyToxic toxic, Instant deadline) {
            calls.add(
                    "add:" + toxic.name() + ":" + toxic.latencyMs() + ":" + toxic.jitterMs() + ":" + toxic.toxicity());
            toxics.add(toxic.name());
        }

        @Override
        public boolean removeLatency(String toxicName, Instant deadline) {
            calls.add("remove:" + toxicName);
            return toxics.remove(toxicName);
        }

        @Override
        public List<Duration> pingThroughProxy(int count, Instant deadline) {
            pingCalls++;
            if (pingCalls == delayedPingCall) {
                sleep(pingDelay);
            }
            Duration each;
            if (toxics.contains(RedisLatencyInjector.TOXIC_NAME)) {
                each = faultPing;
            } else if (slowAfterRemoval > 0) {
                slowAfterRemoval--;
                each = faultPing;
            } else {
                each = healthyPing;
            }
            return Collections.nCopies(count, each);
        }

        @Override
        public synchronized StreamSnapshot readStream(Instant deadline) {
            generated++;
            entries += entriesPerRead;
            return new StreamSnapshot(Instant.now(), generated, 0, entries, lag, 0);
        }

        @Override
        public RedirectProbe probeRedirect(Instant deadline) {
            if (toxics.contains(RedisLatencyInjector.TOXIC_NAME)) {
                return new RedirectProbe(faultProbeSucceeds, faultProbeLatency);
            }
            if (slowRedirectsAfterRemoval > 0
                    && !calls.isEmpty()
                    && calls.getLast().startsWith("remove")) {
                slowRedirectsAfterRemoval--;
                return new RedirectProbe(true, Duration.ofMillis(60));
            }
            return new RedirectProbe(baselineProbeSucceeds, Duration.ofMillis(2));
        }

        private static void sleep(Duration duration) {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
