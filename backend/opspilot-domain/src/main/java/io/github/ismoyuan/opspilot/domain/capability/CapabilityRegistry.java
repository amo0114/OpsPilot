package io.github.ismoyuan.opspilot.domain.capability;

import static io.github.ismoyuan.opspilot.domain.system.ResourceType.CACHE;
import static io.github.ismoyuan.opspilot.domain.system.ResourceType.CONSUMER;
import static io.github.ismoyuan.opspilot.domain.system.ResourceType.DATABASE;
import static io.github.ismoyuan.opspilot.domain.system.ResourceType.EXTERNAL_DEPENDENCY;
import static io.github.ismoyuan.opspilot.domain.system.ResourceType.MESSAGE_QUEUE;
import static io.github.ismoyuan.opspilot.domain.system.ResourceType.SERVICE;

import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * V0.1 冻结的 Capability Registry（08 TASK-044、06 §10～§14）：7 个能力的定义只在 Java 中，不建 capability_definition 表。
 * 是否允许某资源使用某能力由 capability_binding 决定（06 §15），能否执行还要经过资源、类型与唯一 Provider 的校验（06 §16）。
 *
 * <p>适用资源类型与 Provider 取自 06 §37、§48、§59、§69、§83、§93、§102；超时是配置值（06 §125），由装配方传入。
 */
public final class CapabilityRegistry {

    /** 06 §125 的默认超时；实际值由配置决定。 */
    public static final Map<CapabilityKey, Duration> DEFAULT_TIMEOUTS = Map.of(
            CapabilityKey.METRICS_QUERY, Duration.ofSeconds(10),
            CapabilityKey.LOGS_SEARCH, Duration.ofSeconds(15),
            CapabilityKey.CACHE_INSPECT, Duration.ofSeconds(5),
            CapabilityKey.DATABASE_INSPECT, Duration.ofSeconds(10),
            CapabilityKey.QUEUE_INSPECT, Duration.ofSeconds(5),
            CapabilityKey.SERVICE_INSPECT, Duration.ofSeconds(5),
            CapabilityKey.SERVICE_RESTART, Duration.ofSeconds(30));

    private final Map<CapabilityKey, CapabilityDefinition> definitions;

    private CapabilityRegistry(Map<CapabilityKey, CapabilityDefinition> definitions) {
        this.definitions = definitions;
    }

    /**
     * @param timeouts 每个能力的超时，必须覆盖全部 7 个能力
     */
    public static CapabilityRegistry v01(Map<CapabilityKey, Duration> timeouts) {
        Objects.requireNonNull(timeouts, "timeouts");
        Map<CapabilityKey, CapabilityDefinition> definitions = new EnumMap<>(CapabilityKey.class);
        for (CapabilityKey key : CapabilityKey.values()) {
            Duration timeout = timeouts.get(key);
            if (timeout == null) {
                throw new IllegalArgumentException("missing timeout for " + key.key());
            }
            definitions.put(key, define(key, timeout));
        }
        return new CapabilityRegistry(definitions);
    }

    private static CapabilityDefinition define(CapabilityKey key, Duration timeout) {
        return switch (key) {
            case METRICS_QUERY ->
                observe(
                        key,
                        Set.of(SERVICE, CONSUMER, DATABASE, CACHE, MESSAGE_QUEUE, EXTERNAL_DEPENDENCY),
                        ProviderType.PROMETHEUS,
                        timeout);
            case LOGS_SEARCH -> observe(key, Set.of(SERVICE, CONSUMER), ProviderType.LOKI, timeout);
            case CACHE_INSPECT -> observe(key, Set.of(CACHE), ProviderType.REDIS, timeout);
            case DATABASE_INSPECT -> observe(key, Set.of(DATABASE), ProviderType.MYSQL, timeout);
            case QUEUE_INSPECT -> observe(key, Set.of(MESSAGE_QUEUE), ProviderType.REDIS, timeout);
            case SERVICE_INSPECT -> observe(key, Set.of(SERVICE, CONSUMER), ProviderType.DOCKER, timeout);
            case SERVICE_RESTART ->
                new CapabilityDefinition(
                        key,
                        Set.of(SERVICE, CONSUMER),
                        CapabilitySchema.request(key),
                        CapabilitySchema.result(key),
                        Set.of(ProviderType.DOCKER),
                        timeout,
                        true,
                        RiskLevel.MEDIUM);
        };
    }

    private static CapabilityDefinition observe(
            CapabilityKey key, Set<ResourceType> resourceTypes, ProviderType provider, Duration timeout) {
        return new CapabilityDefinition(
                key,
                resourceTypes,
                CapabilitySchema.request(key),
                CapabilitySchema.result(key),
                Set.of(provider),
                timeout,
                false,
                RiskLevel.LOW);
    }

    public CapabilityDefinition definition(CapabilityKey key) {
        return definitions.get(Objects.requireNonNull(key, "key"));
    }

    /** 精确匹配已注册的能力键；未注册（含大小写变体）为空。 */
    public Optional<CapabilityDefinition> find(String key) {
        return CapabilityKey.fromKey(key).map(definitions::get);
    }

    /** 按 CapabilityKey 声明顺序。 */
    public List<CapabilityDefinition> all() {
        return List.copyOf(definitions.values());
    }
}
