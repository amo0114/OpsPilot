package io.github.ismoyuan.opspilot.application.faultlab;

import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.loadRate;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.max;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.min;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.multiply;
import static io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.pause;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.ConsumerContainer;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RuntimeState;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.StreamSnapshot;
import io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.Probes;
import io.github.ismoyuan.opspilot.application.faultlab.FaultSampling.Sample;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S3 statistics-consumer-stop 的真实注入器（08 TASK-093、09 §60～§65）。只在 Demo profile 装配（生产不存在该注入器），且只控制配置绑定的
 * 一个系统与目标资源：其他系统（即使同为 DEMO/TEST、资源同名）不适用，在任何外部动作之前拒绝（B34-R1 P1）。
 *
 * <ol>
 *   <li>inject：Preflight——消费者运行且健康检查通过、Redis 给出 lag 且 lag/pending 在健康区间、跳转可用；Baseline——在持续负载下采样一个
 *       正常窗口（每次采样 lag/pending 仍健康，跳转错误率低于上限，窗口内扣除跳转探测之后的统计消息写入速率不低于
 *       {@link ConsumerStopSettings#minLoadMessageRate()}——负载在运行），记录基线 lag 与跳转 P99；随后经 Docker Engine API 停止消费者容器
 *       （不是 pause），以 Docker 记录的退出时间作为故障生效时间。任一 Preflight 条件不满足即不注入（09 §15）。
 *   <li>verifyInjected：停止后最多等待 {@link ConsumerStopSettings#gateTimeout()}（整体期限：每次调用与等待都裁剪到剩余时间，期限之后完成的
 *       采样不被接受，B34-R1 P2），直到同一次采样同时满足——消费者仍为同一容器且 STOPPED；最近三次采样的最近生成 ID 严格递增，且这段时间内
 *       扣除跳转探测之后的写入速率仍达到负载水平（探测本身是真实访问、会写入统计消息，不能把它当成负载仍在，B34-R1 P1）；最近三次 lag
 *       严格递增；末次 lag 较基线增长至少 {@link ConsumerStopSettings#minLagGrowth()}；停止以来跳转错误率低于上限且 P99 未严重恶化
 *       （09 §65）。成立时刻即首次确认时间；超时则失败，消费者保持停止，由 Reset 恢复。
 *   <li>reset：消费者停止时启动它，在期限内等待其运行且健康检查通过；已在运行（例如经审批的 service.restart 已恢复）时只确认状态。不等待
 *       积压排空：下一次注入的 Preflight 负责确认环境健康（09 §14～§15）。
 * </ol>
 * 三个方法都在数据库事务之外调用。失败说明以阶段开头（Preflight / Gate / Reset），供验收区分环境未达标（SETUP_FAILED）与 OpsPilot 失败；
 * 说明中没有容器 id、端点或采样数值。
 */
public final class StatisticsConsumerStopInjector implements FaultInjector {

    public static final String SCENARIO_KEY = "statistics-consumer-stop";

    /** Baseline 末次采样与停止后检查等非 Gate 调用在阶段期限之外允许的余量。 */
    static final Duration CALL_SLACK = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(StatisticsConsumerStopInjector.class);

    private final ConsumerStopEnvironment environment;
    private final ConsumerStopSettings settings;
    private final String systemKey;
    private final String targetResourceKey;
    private final Clock clock;

    /** inject 与 verifyInjected 之间的单次实验状态（同一系统同一时刻只有一个进行中的实验；进程退出后由启动收束标 FAILED）。 */
    private final Map<Long, Stopped> stopped = new ConcurrentHashMap<>();

    private record Stopped(String containerId, Instant stoppedAt, long baselineLag, Duration baselineP99) {}

    /**
     * @param systemKey 本注入器控制的系统（Demo 靶场所属的 ManagedSystem）
     * @param targetResourceKey 本注入器控制的目标资源（消费者）
     */
    public StatisticsConsumerStopInjector(
            ConsumerStopEnvironment environment,
            ConsumerStopSettings settings,
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
        stopped.remove(target.experimentId());
        Instant baselineDeadline =
                clock.instant().plus(settings.baselineDuration()).plus(CALL_SLACK);
        ConsumerContainer before = requireHealthyConsumer(environment.inspectConsumer(baselineDeadline));
        Baseline baseline = baseline(baselineDeadline);
        ConsumerContainer consumer = requireHealthyConsumer(environment.inspectConsumer(baselineDeadline));
        if (!consumer.containerId().equals(before.containerId())) {
            throw new FaultInjectionException(
                    "Preflight failed: statistics consumer container changed during the baseline");
        }

        Instant stopRequestedAt = clock.instant();
        environment.stopConsumer(
                consumer.containerId(),
                settings.stopGrace(),
                stopRequestedAt.plus(settings.stopGrace()).plus(CALL_SLACK));
        ConsumerContainer after = environment.inspectConsumer(clock.instant().plus(CALL_SLACK));
        if (!consumer.containerId().equals(after.containerId())
                || after.runtimeState() != RuntimeState.STOPPED
                || after.finishedAt() == null
                || after.finishedAt().isBefore(stopRequestedAt)) {
            throw new FaultInjectionException("Statistics consumer was not stopped by the injection");
        }
        stopped.put(
                target.experimentId(),
                new Stopped(consumer.containerId(), after.finishedAt(), baseline.lag(), baseline.p99()));
        log.info("Fault lab stopped the statistics consumer: experimentId={}", target.experimentId());
        return new FaultInjection(after.finishedAt(), consumer.containerId());
    }

    @Override
    public FaultConfirmation verifyInjected(FaultTarget target) {
        requireControlled(target);
        Stopped state = stopped.remove(target.experimentId());
        if (state == null) {
            throw new FaultInjectionException("Gate failed: the injection of this experiment is unknown");
        }
        Duration p99Limit = max(multiply(state.baselineP99(), settings.p99Factor()), settings.p99Floor());
        Instant deadline = state.stoppedAt().plus(settings.gateTimeout());
        List<Sample> samples = new ArrayList<>();
        Probes probes = new Probes();
        String unmet = "no sample before the deadline";
        while (true) {
            if (!clock.instant().isBefore(deadline)) {
                throw gateTimeout(unmet);
            }
            try {
                ConsumerContainer consumer = environment.inspectConsumer(deadline);
                if (!state.containerId().equals(consumer.containerId())) {
                    throw new FaultInjectionException("Gate failed: statistics consumer container changed");
                }
                if (consumer.runtimeState() != RuntimeState.STOPPED) {
                    throw new FaultInjectionException("Gate failed: statistics consumer is no longer stopped");
                }
                samples.add(new Sample(requireLag(environment.readStream(deadline), "Gate failed"), probes.count()));
                probe(probes, deadline);
            } catch (FaultInjectionException ex) {
                // 调用被裁剪到期限而失败：按超时报告
                if (!clock.instant().isBefore(deadline)) {
                    throw gateTimeout(unmet);
                }
                throw ex;
            }
            unmet = unmet(samples, state, probes, p99Limit);
            Instant now = clock.instant();
            // 期限之后才完成的采样不被接受
            if (now.isAfter(deadline)) {
                throw gateTimeout(unmet == null ? "confirmed only after the deadline" : unmet);
            }
            if (unmet == null) {
                log.info("Fault lab confirmed the statistics consumer stop: experimentId={}", target.experimentId());
                return FaultConfirmation.detectedAt(now);
            }
            pause(min(settings.sampleInterval(), Duration.between(now, deadline)));
        }
    }

    @Override
    public void reset(FaultTarget target) {
        requireControlled(target);
        stopped.remove(target.experimentId());
        Instant deadline = clock.instant().plus(settings.resetTimeout());
        ConsumerContainer consumer = environment.inspectConsumer(deadline);
        if (consumer.runtimeState() == RuntimeState.PAUSED) {
            throw new FaultInjectionException("Reset failed: statistics consumer is paused");
        }
        if (consumer.runtimeState() == RuntimeState.STOPPED) {
            environment.startConsumer(consumer.containerId(), deadline);
        }
        while (!environment.inspectConsumer(deadline).healthyRunning()) {
            Instant now = clock.instant();
            if (!now.isBefore(deadline)) {
                throw new FaultInjectionException("Reset failed: statistics consumer did not become healthy");
            }
            pause(min(settings.sampleInterval(), Duration.between(now, deadline)));
        }
        log.info("Fault lab reset the statistics consumer: experimentId={}", target.experimentId());
    }

    private record Baseline(long lag, Duration p99) {}

    /**
     * 09 §16：持续负载下的正常窗口；每次采样都须健康，窗口内扣除跳转探测后的写入速率须达到负载水平（探测本身会产生少量消息，不足以
     * 通过）。
     */
    private Baseline baseline(Instant deadline) {
        Instant end = clock.instant().plus(settings.baselineDuration());
        Sample first = null;
        Sample last;
        Probes probes = new Probes();
        while (true) {
            last = new Sample(requireLag(environment.readStream(deadline), "Preflight failed"), probes.count());
            if (last.stream().lag() > settings.healthyLag()) {
                throw new FaultInjectionException(
                        "Preflight failed: consumer group lag is above the healthy threshold");
            }
            if (last.stream().pending() > settings.healthyPending()) {
                throw new FaultInjectionException(
                        "Preflight failed: consumer group pending is above the healthy threshold");
            }
            probe(probes, deadline);
            if (probes.errorRateAtLeast(settings.maxErrorRate())) {
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
        return new Baseline(last.stream().lag(), probes.p99());
    }

    /** @return 第一个未满足的 Gate 条件；全部满足为空 */
    private String unmet(List<Sample> samples, Stopped state, Probes probes, Duration p99Limit) {
        int size = samples.size();
        if (size < 3) {
            return "fewer than three samples";
        }
        Sample first = samples.get(size - 3);
        StreamSnapshot a = first.stream();
        StreamSnapshot b = samples.get(size - 2).stream();
        Sample last = samples.get(size - 1);
        StreamSnapshot c = last.stream();
        if (!b.generatedAfter(a) || !c.generatedAfter(b)) {
            return "producer is not generating new messages";
        }
        if (loadRate(first, last) < settings.minLoadMessageRate()) {
            return "statistics messages are produced below the expected load rate";
        }
        if (!(a.lag() < b.lag() && b.lag() < c.lag())) {
            return "consumer group lag is not rising";
        }
        if (c.lag() - state.baselineLag() < settings.minLagGrowth()) {
            return "consumer group lag growth is below the threshold";
        }
        if (probes.errorRateAtLeast(settings.maxErrorRate())) {
            return "redirect error rate is at or above the limit";
        }
        if (probes.p99().compareTo(p99Limit) > 0) {
            return "redirect P99 is severely degraded";
        }
        return null;
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

    private ConsumerContainer requireHealthyConsumer(ConsumerContainer consumer) {
        if (!consumer.healthyRunning()) {
            throw new FaultInjectionException("Preflight failed: statistics consumer is not running and healthy");
        }
        return consumer;
    }

    /** 09 §10：Redis 不给 lag 时不能估成 0。 */
    private static StreamSnapshot requireLag(StreamSnapshot snapshot, String stage) {
        if (snapshot.lag() == null) {
            throw new FaultInjectionException(stage + ": Redis did not report the consumer group lag");
        }
        return snapshot;
    }

    private void probe(Probes probes, Instant deadline) {
        for (int i = 0; i < settings.probesPerSample(); i++) {
            probes.add(environment.probeRedirect(deadline));
        }
    }
}
