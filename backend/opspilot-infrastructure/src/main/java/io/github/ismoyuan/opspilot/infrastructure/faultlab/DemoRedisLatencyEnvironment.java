package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static io.github.ismoyuan.opspilot.infrastructure.faultlab.DemoTrafficObserver.CALL_TIMEOUT;
import static io.github.ismoyuan.opspilot.infrastructure.faultlab.DemoTrafficObserver.bounded;
import static io.github.ismoyuan.opspilot.infrastructure.faultlab.DemoTrafficObserver.control;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RedirectProbe;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.StreamSnapshot;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyEnvironment;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * S1 Demo 靶场的真实访问（08 TASK-094）：Toxiproxy 控制 API 管理 latency toxic；经代理（与 ShortLink 相同的监听端口）测量 PING；统计 Stream
 * 与跳转探测由 {@link DemoTrafficObserver} 完成。
 */
final class DemoRedisLatencyEnvironment implements RedisLatencyEnvironment {

    /**
     * 删除 toxic 的单次上限：Toxiproxy 删除 latency toxic 时会等已积压的数据经 toxic 送完，实测在 15 req/s 负载下约 70 秒，远长于普通
     * 调用。
     */
    static final Duration TOXIC_REMOVAL_LIMIT = Duration.ofSeconds(150);

    private final DemoControlClient control;
    private final FaultLabProperties.RedisLatency config;
    private final ToxiproxyClient toxiproxy;
    private final DemoTrafficObserver traffic;
    private final Clock clock;

    DemoRedisLatencyEnvironment(
            DemoControlClient control, ToxiproxyClient toxiproxy, FaultLabProperties.RedisLatency config, Clock clock) {
        this.control = control;
        this.toxiproxy = toxiproxy;
        this.config = config;
        this.clock = clock;
        this.traffic = new DemoTrafficObserver(
                control,
                new DemoTrafficObserver.StreamTarget(
                        config.redisEndpoint(),
                        config.redisUsername(),
                        config.redisPassword(),
                        config.streamKey(),
                        config.consumerGroup()),
                config.redirectProbeUrls(),
                config.probeTimeoutOrDefault(),
                clock);
    }

    @Override
    public ProxyState inspectProxy(Instant deadline) {
        return toxiproxy.proxy(bounded(clock, deadline, CALL_TIMEOUT));
    }

    @Override
    public void addLatency(LatencyToxic toxic, Instant deadline) {
        toxiproxy.addLatency(toxic, bounded(clock, deadline, CALL_TIMEOUT));
    }

    @Override
    public boolean removeLatency(String toxicName, Instant deadline) {
        return toxiproxy.removeToxic(toxicName, bounded(clock, deadline, TOXIC_REMOVAL_LIMIT));
    }

    @Override
    public List<Duration> pingThroughProxy(int count, Instant deadline) {
        Instant bounded = bounded(clock, deadline, CALL_TIMEOUT);
        return control(() -> control.pingLatencies(
                config.proxyRedisEndpoint(), config.redisUsername(), config.redisPassword(), count, bounded));
    }

    @Override
    public StreamSnapshot readStream(Instant deadline) {
        return traffic.readStream(deadline);
    }

    @Override
    public RedirectProbe probeRedirect(Instant deadline) {
        return traffic.probeRedirect(deadline);
    }
}
