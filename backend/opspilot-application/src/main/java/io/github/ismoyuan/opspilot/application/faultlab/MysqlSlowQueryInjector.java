package io.github.ismoyuan.opspilot.application.faultlab;

import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.max;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.min;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.multiply;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.pause;

import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1.MysqlSlowQueryGate;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1.SymptomBranch;
import io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.Probes;
import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQueryEnvironment.PoolState;
import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQueryEnvironment.SlowStatement;
import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQueryEnvironment.WorkloadState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S2 mysql-slow-query 的真实注入器（08 TASK-095、09 §42～§52、§59）。只在 Demo profile 装配，且只控制配置绑定的一个系统与目标资源。故障是
 * project-api 自己的 Hikari Pool 上真实运行的慢存储过程（经 Demo-only 刷新负载），连接池指标、Performance Schema 摘要与创建接口症状都是
 * 实测；OpsPilot 不自开 MySQL 连接占位、不伪造统计。
 *
 * <ol>
 *   <li>inject：Preflight——刷新负载未运行、连接池 pending 为 0 且 active 低于饱和下限、慢语句摘要没有残留执行（上次已 Reset）；Baseline——
 *       持续创建负载下的正常窗口（每次采样 pending 为 0、active 低于饱和下限、创建错误率低于上限、扣除探测后的创建速率达到负载水平），记录
 *       创建基线 P99 与错误率；随后启动刷新负载，以 project-api 确认启动的时刻作为故障生效时间。
 *   <li>verifyInjected：最多 {@link MysqlSlowQuerySettings#gateTimeout()}（整体期限，迟到采样不被接受），直到同时满足——刷新负载仍在
 *       运行；连续 {@link MysqlSlowQuerySettings#saturatedSamples()} 次 active ≥ 饱和下限；至少
 *       {@link MysqlSlowQuerySettings#pendingSamples()} 次 pending > 0；慢语句摘要平均或最大耗时 ≥ 下限；创建 LATENCY 或 ERROR_RATE 分支
 *       成立（09 §51）。确认结果记录实际配方、实测与所达分支，写入 Ground Truth。未达标即 SETUP_FAILED，负载保留由 Reset 停止。
 *   <li>reset：停止刷新负载并等实际工作结束，等连接池 pending 为 0、active 回落，再以 Demo 控制凭证清理语句摘要，最后确认创建探测全部成功
 *       且 P99 不高于显式的健康上限（09 §59）。
 * </ol>
 */
public final class MysqlSlowQueryInjector implements FaultInjector {

    public static final String SCENARIO_KEY = "mysql-slow-query";

    static final Duration CALL_SLACK = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(MysqlSlowQueryInjector.class);

    private final MysqlSlowQueryEnvironment environment;
    private final MysqlSlowQuerySettings settings;
    private final String systemKey;
    private final String targetResourceKey;
    private final Clock clock;
    private final Map<Long, Injected> injected = new ConcurrentHashMap<>();

    private record Injected(Instant startedAt, Duration baselineP99, double baselineErrorRate) {}

    public MysqlSlowQueryInjector(
            MysqlSlowQueryEnvironment environment,
            MysqlSlowQuerySettings settings,
            String systemKey,
            String targetResourceKey,
            Clock clock) {
        this.environment = environment;
        this.settings = settings;
        this.systemKey = Objects.requireNonNull(systemKey, "systemKey");
        this.targetResourceKey = Objects.requireNonNull(targetResourceKey, "targetResourceKey");
        this.clock = clock;
    }

    @Override
    public String scenarioKey() {
        return SCENARIO_KEY;
    }

    @Override
    public boolean controls(String requestedSystemKey, String requestedTargetResourceKey) {
        return systemKey.equals(requestedSystemKey) && targetResourceKey.equals(requestedTargetResourceKey);
    }

    @Override
    public FaultInjection inject(FaultTarget target) {
        requireControlled(target);
        injected.remove(target.experimentId());
        Instant deadline = clock.instant().plus(settings.baselineDuration()).plus(CALL_SLACK);
        if (environment.workload(deadline).running()) {
            throw new FaultInjectionException("Preflight failed: the snapshot workload is already running");
        }
        if (environment.slowStatement(deadline).executions() > 0) {
            throw new FaultInjectionException("Preflight failed: the statement summary still holds slow statements");
        }
        Probes baseline = baseline(deadline);

        environment.startWorkload(
                settings.workers(), settings.statementMillis(), clock.instant().plus(CALL_SLACK));
        Instant startedAt = clock.instant();
        injected.put(target.experimentId(), new Injected(startedAt, baseline.p99(), baseline.errorRate()));
        log.info("Fault lab started the snapshot workload: experimentId={}", target.experimentId());
        return FaultInjection.startedAt(startedAt);
    }

    @Override
    public FaultConfirmation verifyInjected(FaultTarget target) {
        requireControlled(target);
        Injected state = injected.remove(target.experimentId());
        if (state == null) {
            throw new FaultInjectionException("Gate failed: the injection of this experiment is unknown");
        }
        Duration p99Limit = max(multiply(state.baselineP99(), settings.p99Factor()), settings.p99Floor());
        double errorLimit =
                Math.max(state.baselineErrorRate() + settings.errorRateIncrease(), settings.errorRateFloor());
        Instant deadline = state.startedAt().plus(settings.gateTimeout());
        Probes fault = new Probes();
        int saturatedRun = 0;
        int maxSaturatedRun = 0;
        int pendingSamples = 0;
        int maxActive = 0;
        int maxPending = 0;
        String unmet = "no sample before the deadline";
        while (true) {
            if (!clock.instant().isBefore(deadline)) {
                throw gateTimeout(unmet);
            }
            PoolState pool;
            SlowStatement slow;
            try {
                if (!environment.workload(deadline).running()) {
                    throw new FaultInjectionException("Gate failed: the snapshot workload is no longer running");
                }
                pool = environment.pool(deadline);
                slow = environment.slowStatement(deadline);
                probe(fault, deadline);
            } catch (FaultInjectionException ex) {
                if (!clock.instant().isBefore(deadline)) {
                    throw gateTimeout(unmet);
                }
                throw ex;
            }
            saturatedRun = pool.active() >= settings.saturatedActive() ? saturatedRun + 1 : 0;
            maxSaturatedRun = Math.max(maxSaturatedRun, saturatedRun);
            if (pool.pending() > 0) {
                pendingSamples++;
            }
            maxActive = Math.max(maxActive, pool.active());
            maxPending = Math.max(maxPending, pool.pending());
            boolean latency = fault.p99().compareTo(p99Limit) >= 0;
            boolean errors = fault.errorRate() >= errorLimit;
            long slowMillis = Math.max(slow.avgMillis(), slow.maxMillis());
            if (maxSaturatedRun < settings.saturatedSamples()) {
                unmet = "the connection pool is not saturated for consecutive samples";
            } else if (pendingSamples < settings.pendingSamples()) {
                unmet = "too few samples with pending connection requests";
            } else if (slow.executions() == 0
                    || slowMillis < settings.slowStatementMillis().toMillis()) {
                unmet = "the slow statement summary is below the gate";
            } else if (!latency && !errors) {
                unmet = "create symptoms are below both gate branches";
            } else {
                unmet = null;
            }
            Instant now = clock.instant();
            if (now.isAfter(deadline)) {
                throw gateTimeout(unmet == null ? "confirmed only after the deadline" : unmet);
            }
            if (unmet == null) {
                SymptomBranch branch = latency && errors
                        ? SymptomBranch.BOTH
                        : latency ? SymptomBranch.LATENCY : SymptomBranch.ERROR_RATE;
                log.info(
                        "Fault lab confirmed the snapshot workload: experimentId={} branch={}",
                        target.experimentId(),
                        branch);
                return new FaultConfirmation(
                        now,
                        null,
                        new MysqlSlowQueryGate(
                                branch,
                                settings.workers(),
                                settings.statementMillis(),
                                maxActive,
                                maxSaturatedRun,
                                pendingSamples,
                                maxPending,
                                slow.avgMillis(),
                                slow.maxMillis(),
                                state.baselineP99().toMillis(),
                                fault.p99().toMillis(),
                                state.baselineErrorRate(),
                                fault.errorRate()));
            }
            pause(min(settings.sampleInterval(), Duration.between(now, deadline)));
        }
    }

    @Override
    public void reset(FaultTarget target) {
        requireControlled(target);
        injected.remove(target.experimentId());
        Instant deadline = clock.instant().plus(settings.resetTimeout());
        try {
            environment.stopWorkload(deadline);
            // 先等负载与连接池真正回落，再清理摘要：清理之后不应再有慢语句写入
            while (!settled(deadline)) {
                waitOrFail(deadline);
            }
            environment.clearStatementSummary(deadline);
            while (!createRecovered(deadline)) {
                waitOrFail(deadline);
            }
        } catch (FaultInjectionException ex) {
            if (!clock.instant().isBefore(deadline)) {
                throw notRecovered();
            }
            throw ex;
        }
        log.info("Fault lab reset the snapshot workload: experimentId={}", target.experimentId());
    }

    /** 刷新负载已停且无进行中的刷新，连接池 pending 为 0、active 低于饱和下限。 */
    private boolean settled(Instant deadline) {
        WorkloadState workload = environment.workload(deadline);
        if (workload.running() || workload.inFlight() > 0) {
            return false;
        }
        PoolState pool = environment.pool(deadline);
        return pool.pending() == 0 && pool.active() < settings.saturatedActive();
    }

    /** 09 §59：创建接口恢复正常范围——本轮探测全部成功且 P99 不高于健康上限。 */
    private boolean createRecovered(Instant deadline) {
        Probes probes = new Probes();
        probe(probes, deadline);
        return probes.errorRate() == 0 && probes.p99().compareTo(settings.healthyCreateP99()) <= 0;
    }

    private void waitOrFail(Instant deadline) {
        Instant now = clock.instant();
        if (!now.isBefore(deadline)) {
            throw notRecovered();
        }
        pause(min(settings.sampleInterval(), Duration.between(now, deadline)));
    }

    /** 09 §16：持续创建负载下的正常窗口。@return 窗口内的创建探测（基线 P99 与错误率） */
    private Probes baseline(Instant deadline) {
        Instant end = clock.instant().plus(settings.baselineDuration());
        Probes probes = new Probes();
        Instant firstAt = null;
        long firstCount = 0;
        long firstProbes = 0;
        Instant lastAt;
        long lastCount;
        long lastProbes;
        while (true) {
            PoolState pool = environment.pool(deadline);
            if (pool.pending() > 0 || pool.active() >= settings.saturatedActive()) {
                throw new FaultInjectionException("Preflight failed: the connection pool is already contended");
            }
            lastCount = environment.createRequests(deadline);
            lastAt = clock.instant();
            lastProbes = probes.count();
            probe(probes, deadline);
            if (probes.errorRateAtLeast(settings.maxBaselineErrorRate())) {
                throw new FaultInjectionException("Preflight failed: create requests are failing");
            }
            if (firstAt == null) {
                firstAt = lastAt;
                firstCount = lastCount;
                firstProbes = lastProbes;
            }
            if (!clock.instant().isBefore(end)) {
                break;
            }
            pause(settings.sampleInterval());
        }
        double seconds = Duration.between(firstAt, lastAt).toNanos() / 1e9;
        double rate = seconds <= 0 ? 0 : (lastCount - firstCount - (lastProbes - firstProbes)) / seconds;
        if (rate < settings.minLoadRate()) {
            throw new FaultInjectionException("Preflight failed: create requests arrive below the expected load rate");
        }
        return probes;
    }

    private void probe(Probes probes, Instant deadline) {
        for (int i = 0; i < settings.probesPerSample(); i++) {
            probes.add(environment.probeCreate(deadline));
        }
    }

    private void requireControlled(FaultTarget target) {
        if (!controls(target.systemKey(), target.targetResourceKey())) {
            throw new FaultInjectionException("Fault lab control is not bound to the requested system");
        }
    }

    private FaultInjectionException gateTimeout(String unmet) {
        return new FaultInjectionException(
                "Gate failed: not met within " + settings.gateTimeout().toSeconds() + " seconds (" + unmet + ")");
    }

    private static FaultInjectionException notRecovered() {
        return new FaultInjectionException("Reset failed: the snapshot workload or create requests did not recover");
    }
}
