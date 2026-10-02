package io.github.ismoyuan.opspilot.application.faultlab;

import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.loadRate;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.max;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.min;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.multiply;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.pause;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.StreamSnapshot;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1.RedisLatencyGate;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1.SymptomBranch;
import io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.Probes;
import io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.Sample;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyEnvironment.LatencyToxic;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyEnvironment.ProxyState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S1 redis-latency 的真实注入器（08 TASK-094、09 §28～§33、§41）。只在 Demo profile 装配，且只控制配置绑定的一个系统与目标资源（B34-R1
 * 规则）。故障是 Toxiproxy 上真实的网络延迟：ShortLink 与 OpsPilot cache.inspect 经同一代理访问 Redis（09 §29），OpsPilot 不修改 Provider
 * 返回值、不 sleep 模拟延迟、不伪造 Observation。
 *
 * <ol>
 *   <li>inject：Preflight——代理启用且没有任何 toxic，经代理 PING 中位数在正常范围；Baseline——持续负载下的正常窗口（每次采样 lag/pending
 *       健康、跳转错误率低于上限、扣除探测后的写入速率达到负载水平），记录跳转基线 P99 与错误率；随后增加 downstream latency toxic，以代理
 *       确认 toxic 建立的时刻作为故障生效时间。
 *   <li>verifyInjected：最多 {@link RedisLatencySettings#gateTimeout()}（整体期限：调用与等待裁剪到剩余时间，期限后完成的采样不被接受），
 *       直到同一次采样同时满足——toxic 仍在；本次 {@link RedisLatencySettings#pingCount()} 次经代理 PING 的中位数 ≥
 *       {@link RedisLatencySettings#faultPingMedian()}；故障期累计跳转探测达到下限，且 LATENCY（P99 ≥ max(基线 × 系数, 下限)）或
 *       ERROR_RATE（错误率 ≥ max(基线 + 增量, 下限)）分支成立（09 §33）。确认结果记录实际达标分支（LATENCY / ERROR_RATE / BOTH）与真实
 *       数值，写入 Ground Truth 供 Evaluation 以同一分支断言 HTTP 证据。Redis 变慢但业务无影响即未达标（SETUP_FAILED），toxic 保留由 Reset
 *       删除。
 *   <li>reset：删除本场景的 toxic（不存在也可），在期限内等待 toxic 消失、经代理 PING 中位数回到正常范围、本轮跳转探测全部成功且 P99
 *       不高于显式的健康上限（09 §41：Redis RTT 与 HTTP P99 均恢复；判据不依赖注入时的内存状态）。
 * </ol>
 * 失败说明以阶段开头（Preflight / Gate / Reset），不含端点或数值。
 */
public final class RedisLatencyInjector implements FaultInjector {

    public static final String SCENARIO_KEY = "redis-latency";

    /** 本场景在代理上使用的 toxic 名称（只在 Toxiproxy 控制面可见）。 */
    public static final String TOXIC_NAME = "fault-lab-redis-latency";

    /** Baseline 末次采样等非 Gate 调用在阶段期限之外允许的余量。 */
    static final Duration CALL_SLACK = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(RedisLatencyInjector.class);

    private final RedisLatencyEnvironment environment;
    private final RedisLatencySettings settings;
    private final String systemKey;
    private final String targetResourceKey;
    private final Clock clock;

    /** inject 与 verifyInjected 之间的单次实验状态。 */
    private final Map<Long, Injected> injected = new ConcurrentHashMap<>();

    private record Injected(Instant startedAt, Duration baselineP99, double baselineErrorRate) {}

    public RedisLatencyInjector(
            RedisLatencyEnvironment environment,
            RedisLatencySettings settings,
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
        Instant baselineDeadline =
                clock.instant().plus(settings.baselineDuration()).plus(CALL_SLACK);
        ProxyState proxy = environment.inspectProxy(baselineDeadline);
        if (!proxy.enabled()) {
            throw new FaultInjectionException("Preflight failed: the Redis proxy is disabled");
        }
        if (!proxy.toxicNames().isEmpty()) {
            throw new FaultInjectionException("Preflight failed: the Redis proxy already has toxics");
        }
        if (median(environment.pingThroughProxy(settings.pingCount(), baselineDeadline))
                        .compareTo(settings.healthyPingMedian())
                > 0) {
            throw new FaultInjectionException(
                    "Preflight failed: the Redis round trip through the proxy is not healthy");
        }
        Probes baseline = baseline(baselineDeadline);

        environment.addLatency(
                new LatencyToxic(TOXIC_NAME, settings.latencyMs(), settings.jitterMs(), settings.toxicity()),
                clock.instant().plus(CALL_SLACK));
        Instant startedAt = clock.instant();
        injected.put(target.experimentId(), new Injected(startedAt, baseline.p99(), baseline.errorRate()));
        log.info("Fault lab added the Redis latency toxic: experimentId={}", target.experimentId());
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
        String unmet = "no sample before the deadline";
        while (true) {
            if (!clock.instant().isBefore(deadline)) {
                throw gateTimeout(unmet);
            }
            Duration pingMedian;
            try {
                if (!environment.inspectProxy(deadline).toxicNames().contains(TOXIC_NAME)) {
                    throw new FaultInjectionException("Gate failed: the latency toxic is no longer present");
                }
                pingMedian = median(environment.pingThroughProxy(settings.pingCount(), deadline));
                probe(fault, deadline);
            } catch (FaultInjectionException ex) {
                if (!clock.instant().isBefore(deadline)) {
                    throw gateTimeout(unmet);
                }
                throw ex;
            }
            boolean latency = fault.p99().compareTo(p99Limit) >= 0;
            boolean errors = fault.errorRate() >= errorLimit;
            if (pingMedian.compareTo(settings.faultPingMedian()) < 0) {
                unmet = "the Redis round trip through the proxy is below the gate";
            } else if (fault.count() < settings.minFaultProbes()) {
                unmet = "fewer redirect probes than required";
            } else if (!latency && !errors) {
                unmet = "redirect symptoms are below both gate branches";
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
                        "Fault lab confirmed the Redis latency: experimentId={} branch={}",
                        target.experimentId(),
                        branch);
                return new FaultConfirmation(
                        now,
                        new RedisLatencyGate(
                                branch,
                                settings.latencyMs(),
                                pingMedian.toMillis(),
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
        environment.removeLatency(TOXIC_NAME, deadline);
        while (!recoveredBefore(deadline)) {
            Instant now = clock.instant();
            if (!now.isBefore(deadline)) {
                throw notRecovered();
            }
            pause(min(settings.sampleInterval(), Duration.between(now, deadline)));
        }
        log.info("Fault lab reset the Redis latency: experimentId={}", target.experimentId());
    }

    /** 恢复检查；其中的调用因 Reset 期限被截断时按“未恢复”报告。 */
    private boolean recoveredBefore(Instant deadline) {
        try {
            return recovered(deadline);
        } catch (FaultInjectionException ex) {
            if (!clock.instant().isBefore(deadline)) {
                throw notRecovered();
            }
            throw ex;
        }
    }

    private static FaultInjectionException notRecovered() {
        return new FaultInjectionException("Reset failed: the Redis round trip or redirects did not recover");
    }

    /** 09 §41：toxic 已不在、经代理 RTT 回到正常范围、本轮跳转全部成功且 P99 不高于 {@link RedisLatencySettings#healthyRedirectP99()}。 */
    private boolean recovered(Instant deadline) {
        if (environment.inspectProxy(deadline).toxicNames().contains(TOXIC_NAME)) {
            return false;
        }
        if (median(environment.pingThroughProxy(settings.pingCount(), deadline)).compareTo(settings.healthyPingMedian())
                > 0) {
            return false;
        }
        Probes probes = new Probes();
        probe(probes, deadline);
        return probes.errorRate() == 0 && probes.p99().compareTo(settings.healthyRedirectP99()) <= 0;
    }

    /** 09 §16：持续负载下的正常窗口。@return 窗口内的跳转探测（基线 P99 与错误率） */
    private Probes baseline(Instant deadline) {
        Instant end = clock.instant().plus(settings.baselineDuration());
        Sample first = null;
        Sample last;
        Probes probes = new Probes();
        while (true) {
            StreamSnapshot stream = environment.readStream(deadline);
            if (stream.lag() == null) {
                throw new FaultInjectionException("Preflight failed: Redis did not report the consumer group lag");
            }
            last = new Sample(stream, probes.count());
            if (stream.lag() > settings.healthyLag()) {
                throw new FaultInjectionException(
                        "Preflight failed: consumer group lag is above the healthy threshold");
            }
            if (stream.pending() > settings.healthyPending()) {
                throw new FaultInjectionException(
                        "Preflight failed: consumer group pending is above the healthy threshold");
            }
            probe(probes, deadline);
            if (probes.errorRateAtLeast(settings.maxBaselineErrorRate())) {
                throw new FaultInjectionException("Preflight failed: redirect requests are failing");
            }
            if (first == null) {
                first = last;
            }
            if (!clock.instant().isBefore(end)) {
                break;
            }
            pause(settings.sampleInterval());
        }
        if (!last.stream().generatedAfter(first.stream()) || loadRate(first, last) < settings.minLoadMessageRate()) {
            throw new FaultInjectionException(
                    "Preflight failed: statistics messages are produced below the expected load rate");
        }
        return probes;
    }

    private void probe(Probes probes, Instant deadline) {
        for (int i = 0; i < settings.probesPerSample(); i++) {
            probes.add(environment.probeRedirect(deadline));
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

    /** 中位数；偶数个时取较大的中间值。 */
    static Duration median(List<Duration> samples) {
        if (samples.isEmpty()) {
            throw new FaultInjectionException("Demo environment: no ping round trip was measured");
        }
        List<Duration> sorted = samples.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }
}
