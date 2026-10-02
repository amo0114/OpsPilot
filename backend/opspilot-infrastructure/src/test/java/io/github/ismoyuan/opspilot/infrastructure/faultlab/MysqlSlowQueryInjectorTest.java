package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RedirectProbe;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1.SymptomBranch;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.application.faultlab.FaultTarget;
import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQueryEnvironment;
import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQueryInjector;
import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQuerySettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * S2 注入器的 Preflight、Baseline、Gate 与 Reset（08 TASK-095、09 §49～§51、§59），用脚本化的靶场替身；真实 MySQL＋真实 Hikari Pool 见
 * {@link MysqlSlowQueryInjectorIntegrationTest}。
 */
class MysqlSlowQueryInjectorTest {

    static final FaultTarget TARGET =
            new FaultTarget(41, "mysql-slow-query", "shortlink-platform", 6, "shortlink-mysql");

    /** 毫秒级窗口：Baseline 40ms、采样 5ms、Gate 400ms、Reset 300ms；P99 下限 80ms、Reset 健康上限 20ms、慢语句下限 2000ms。 */
    static final MysqlSlowQuerySettings FAST = new MysqlSlowQuerySettings(
            8,
            3000,
            Duration.ofMillis(40),
            Duration.ofMillis(5),
            1,
            5.0,
            0.01,
            7,
            3,
            2,
            Duration.ofMillis(2000),
            5.0,
            Duration.ofMillis(80),
            0.05,
            0.05,
            Duration.ofMillis(400),
            Duration.ofMillis(20),
            Duration.ofMillis(300));

    private final Fake environment = new Fake();
    private final MysqlSlowQueryInjector injector =
            new MysqlSlowQueryInjector(environment, FAST, "shortlink-platform", "shortlink-mysql", Clock.systemUTC());

    @Test
    void poolContentionWithSlowStatementsAndSlowCreatesConfirmsTheLatencyBranch() {
        environment.faultCreateLatency = Duration.ofMillis(120);

        var injection = injector.inject(TARGET);
        var confirmation = injector.verifyInjected(TARGET);

        assertThat(environment.calls).containsExactly("start:8:3000");
        assertThat(confirmation.detectedAt())
                .isAfterOrEqualTo(injection.startedAt())
                .isBeforeOrEqualTo(injection.startedAt().plus(FAST.gateTimeout()));
        var gate = confirmation.mysqlSlowQueryGate();
        assertThat(confirmation.redisLatencyGate()).isNull();
        assertThat(gate.symptomBranch()).isEqualTo(SymptomBranch.LATENCY);
        assertThat(gate.workers()).isEqualTo(8);
        assertThat(gate.statementMillis()).isEqualTo(3000);
        assertThat(gate.maxActive()).isEqualTo(8);
        assertThat(gate.saturatedSamples()).isGreaterThanOrEqualTo(3);
        assertThat(gate.pendingSamples()).isGreaterThanOrEqualTo(2);
        assertThat(gate.maxPending()).isEqualTo(5);
        assertThat(gate.slowStatementAvgMs()).isEqualTo(3000);
        assertThat(gate.slowStatementMaxMs()).isEqualTo(3001);
        assertThat(gate.baselineP99Ms()).isEqualTo(2);
        assertThat(gate.faultP99Ms()).isEqualTo(120);
    }

    @Test
    void failingCreatesConfirmTheErrorRateBranch() {
        environment.faultCreateSucceeds = false;
        injector.inject(TARGET);

        assertThat(injector.verifyInjected(TARGET).mysqlSlowQueryGate().symptomBranch())
                .isEqualTo(SymptomBranch.ERROR_RATE);
    }

    @Test
    void eachGateConditionIsRequired() {
        environment.faultCreateLatency = Duration.ofMillis(120);
        environment.faultActive = 7;
        environment.faultPending = 0;
        assertGateFails("too few samples with pending connection requests");

        environment.faultActive = 6;
        environment.faultPending = 3;
        assertGateFails("the connection pool is not saturated for consecutive samples");

        environment.faultActive = 8;
        environment.slowMaxMillis = 1500;
        environment.slowAvgMillis = 1500;
        assertGateFails("the slow statement summary is below the gate");

        environment.slowMaxMillis = 3001;
        environment.slowAvgMillis = 3000;
        environment.faultCreateLatency = Duration.ofMillis(2);
        assertGateFails("create symptoms are below both gate branches");
    }

    @Test
    void aStoppedWorkloadFailsTheGate() {
        injector.inject(TARGET);
        environment.running = false;

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .hasMessage("Gate failed: the snapshot workload is no longer running");
    }

    @Test
    void aSampleCompletedAfterTheDeadlineIsNotAccepted() {
        environment.faultCreateLatency = Duration.ofMillis(120);
        injector.inject(TARGET);
        // 第 3 次 Gate 采样（首次可能满足全部条件）的连接池读取返回时已超过 400ms 期限
        environment.delayedPoolRead = environment.poolReads + 3;

        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .hasMessage("Gate failed: not met within 0 seconds (confirmed only after the deadline)");
    }

    @Test
    void preflightRefusesAnUnhealthyEnvironmentWithoutStarting() {
        environment.running = true;
        assertPreflightFails("the snapshot workload is already running");

        environment.running = false;
        environment.leftoverExecutions = 3;
        assertPreflightFails("the statement summary still holds slow statements");

        environment.leftoverExecutions = 0;
        environment.baselinePending = 1;
        assertPreflightFails("the connection pool is already contended");

        environment.baselinePending = 0;
        environment.createsPerRead = 0;
        assertPreflightFails("create requests arrive below the expected load rate");

        environment.createsPerRead = 10;
        environment.baselineCreateSucceeds = false;
        assertPreflightFails("create requests are failing");
    }

    @Test
    void otherSystemsAndResourcesAreNotControlled() {
        FaultTarget other = new FaultTarget(42, "mysql-slow-query", "test-platform", 9, "shortlink-mysql");
        for (FaultTarget target : List.of(other)) {
            assertThatThrownBy(() -> injector.inject(target))
                    .hasMessage("Fault lab control is not bound to the requested system");
            assertThatThrownBy(() -> injector.verifyInjected(target))
                    .hasMessage("Fault lab control is not bound to the requested system");
            assertThatThrownBy(() -> injector.reset(target))
                    .hasMessage("Fault lab control is not bound to the requested system");
        }
        assertThat(environment.calls).isEmpty();
    }

    /** 09 §59：停止负载 → 等实际工作结束与连接池回落 → 清理摘要 → 创建恢复（P99 回到健康上限以内）。 */
    @Test
    void resetStopsWaitsForThePoolClearsTheSummaryAndConfirmsCreates() {
        injector.inject(TARGET);
        environment.inFlightAfterStop = 3;
        environment.slowCreatesAfterClear = 3;

        injector.reset(TARGET);

        assertThat(environment.calls).containsExactly("start:8:3000", "stop", "clear");
        assertThat(environment.inFlightAfterStop).isZero();
        assertThat(environment.slowCreatesAfterClear).isZero();
    }

    @Test
    void resetFailsWhileCreatesStaySlow() {
        injector.inject(TARGET);
        environment.slowCreatesAfterClear = Integer.MAX_VALUE;

        assertThatThrownBy(() -> injector.reset(TARGET))
                .hasMessage("Reset failed: the snapshot workload or create requests did not recover");
    }

    private void assertGateFails(String unmet) {
        injector.inject(TARGET);
        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageStartingWith("Gate failed: not met within")
                .hasMessageContaining(unmet);
        environment.running = false;
    }

    private void assertPreflightFails(String message) {
        assertThatThrownBy(() -> injector.inject(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageStartingWith("Preflight failed")
                .hasMessageContaining(message);
        assertThat(environment.calls).noneMatch(call -> call.startsWith("start"));
    }

    /** 脚本化的靶场：负载运行时连接池为 faultActive/faultPending，创建按 fault* 配置；未运行时 active 2、pending 0、创建 2ms 成功。 */
    static final class Fake implements MysqlSlowQueryEnvironment {

        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile boolean running;
        volatile int faultActive = 8;
        volatile int faultPending = 5;
        volatile int baselinePending;
        volatile long leftoverExecutions;
        volatile long slowAvgMillis = 3000;
        volatile long slowMaxMillis = 3001;
        volatile long createsPerRead = 10;
        volatile boolean baselineCreateSucceeds = true;
        volatile boolean faultCreateSucceeds = true;
        volatile Duration faultCreateLatency = Duration.ofMillis(2);
        volatile int inFlightAfterStop;
        volatile int slowCreatesAfterClear;
        volatile int poolReads;
        volatile int delayedPoolRead = -1;
        private long creates;
        private boolean cleared;

        @Override
        public WorkloadState workload(Instant deadline) {
            if (!running && inFlightAfterStop > 0) {
                inFlightAfterStop--;
                return new WorkloadState(false, 1);
            }
            return new WorkloadState(running, running ? 8 : 0);
        }

        @Override
        public void startWorkload(int workers, int statementMillis, Instant deadline) {
            calls.add("start:" + workers + ":" + statementMillis);
            running = true;
            cleared = false;
        }

        @Override
        public WorkloadState stopWorkload(Instant deadline) {
            calls.add("stop");
            running = false;
            return new WorkloadState(false, inFlightAfterStop);
        }

        @Override
        public PoolState pool(Instant deadline) {
            poolReads++;
            if (poolReads == delayedPoolRead) {
                try {
                    Thread.sleep(450);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return running ? new PoolState(faultActive, faultPending, 8) : new PoolState(2, baselinePending, 8);
        }

        @Override
        public SlowStatement slowStatement(Instant deadline) {
            if (running) {
                return new SlowStatement(5, slowAvgMillis, slowMaxMillis);
            }
            return new SlowStatement(cleared ? 0 : leftoverExecutions, 0, 0);
        }

        @Override
        public void clearStatementSummary(Instant deadline) {
            calls.add("clear");
            cleared = true;
        }

        @Override
        public synchronized long createRequests(Instant deadline) {
            creates += createsPerRead;
            return creates;
        }

        @Override
        public RedirectProbe probeCreate(Instant deadline) {
            if (running) {
                return new RedirectProbe(faultCreateSucceeds, faultCreateLatency);
            }
            if (cleared && slowCreatesAfterClear > 0) {
                slowCreatesAfterClear--;
                return new RedirectProbe(true, Duration.ofMillis(60));
            }
            return new RedirectProbe(baselineCreateSucceeds, Duration.ofMillis(2));
        }
    }
}
