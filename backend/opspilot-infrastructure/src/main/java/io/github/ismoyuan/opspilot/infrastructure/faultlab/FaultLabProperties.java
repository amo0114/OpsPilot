package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopSettings;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fault Lab 真实注入器的 Demo 控制面配置（08 TASK-093）。只在 demo profile 中给出并启用（application-demo.yml）；默认与生产配置没有
 * 这些值，也就没有注入器（05 §68）。端点与凭据属于 Fault Lab 自己，与被调查资源的数据源连接分开。
 */
@ConfigurationProperties("opspilot.fault-lab")
public record FaultLabProperties(StatisticsConsumerStop statisticsConsumerStop) {

    /**
     * {@code opspilot.fault-lab.statistics-consumer-stop.*}：S3。Gate 参数未配置时取 {@link ConsumerStopSettings#DEFAULTS}。
     *
     * @param systemKey 这套 Demo 靶场所属的 ManagedSystem；注入器只控制它（B34-R1 P1）
     * @param targetResourceKey 该系统中的目标资源（消费者），默认 statistics-consumer
     * @param dockerEndpoint 仅支持 unix:///path/to/docker.sock
     * @param containerName 消费者容器名
     * @param redisEndpoint redis://host:port（直连靶场 Redis，不经 S1 的 Toxiproxy）
     * @param redisUsername 可空；有密码时用 ACL 账号认证
     * @param redisPassword 可空
     * @param redirectProbeUrls 演示短链完整地址（Host 须与 ShortLink 域名配置一致）；Preflight 要求至少一个
     * @param probeTimeout 单次跳转探测超时，默认 2 秒
     */
    public record StatisticsConsumerStop(
            boolean enabled,
            String systemKey,
            String targetResourceKey,
            String dockerEndpoint,
            String containerName,
            String redisEndpoint,
            String redisUsername,
            String redisPassword,
            String streamKey,
            String consumerGroup,
            List<URI> redirectProbeUrls,
            Duration probeTimeout,
            Duration baselineDuration,
            Duration sampleInterval,
            Integer probesPerSample,
            Double minLoadMessageRate,
            Long healthyLag,
            Long healthyPending,
            Duration stopGrace,
            Duration gateTimeout,
            Long minLagGrowth,
            Double maxErrorRate,
            Double p99Factor,
            Duration p99Floor,
            Duration resetTimeout) {

        public static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(2);

        public static final String DEFAULT_TARGET_RESOURCE_KEY = "statistics-consumer";

        public StatisticsConsumerStop {
            redirectProbeUrls = redirectProbeUrls == null ? List.of() : List.copyOf(redirectProbeUrls);
        }

        public String targetResourceKeyOrDefault() {
            return targetResourceKey == null || targetResourceKey.isBlank()
                    ? DEFAULT_TARGET_RESOURCE_KEY
                    : targetResourceKey;
        }

        public Duration probeTimeoutOrDefault() {
            return probeTimeout == null ? DEFAULT_PROBE_TIMEOUT : probeTimeout;
        }

        ConsumerStopSettings settings() {
            ConsumerStopSettings d = ConsumerStopSettings.DEFAULTS;
            return new ConsumerStopSettings(
                    or(baselineDuration, d.baselineDuration()),
                    or(sampleInterval, d.sampleInterval()),
                    or(probesPerSample, d.probesPerSample()),
                    or(minLoadMessageRate, d.minLoadMessageRate()),
                    or(healthyLag, d.healthyLag()),
                    or(healthyPending, d.healthyPending()),
                    or(stopGrace, d.stopGrace()),
                    or(gateTimeout, d.gateTimeout()),
                    or(minLagGrowth, d.minLagGrowth()),
                    or(maxErrorRate, d.maxErrorRate()),
                    or(p99Factor, d.p99Factor()),
                    or(p99Floor, d.p99Floor()),
                    or(resetTimeout, d.resetTimeout()));
        }

        private static <T> T or(T value, T fallback) {
            return value == null ? fallback : value;
        }
    }
}
