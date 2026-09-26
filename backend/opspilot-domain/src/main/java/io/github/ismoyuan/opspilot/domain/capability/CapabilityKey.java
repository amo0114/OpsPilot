package io.github.ismoyuan.opspilot.domain.capability;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * V0.1 冻结的 7 个 Capability 及其 mode（06 §10～§11）。只有这里列出的键是真实能力；
 * 其余描述字段（资源类型、Schema、Provider、超时、风险）由 CapabilityRegistry（TASK-044）补充。
 */
public enum CapabilityKey {
    METRICS_QUERY("metrics.query", CapabilityMode.OBSERVE),
    LOGS_SEARCH("logs.search", CapabilityMode.OBSERVE),
    CACHE_INSPECT("cache.inspect", CapabilityMode.OBSERVE),
    DATABASE_INSPECT("database.inspect", CapabilityMode.OBSERVE),
    QUEUE_INSPECT("queue.inspect", CapabilityMode.OBSERVE),
    SERVICE_INSPECT("service.inspect", CapabilityMode.OBSERVE),
    SERVICE_RESTART("service.restart", CapabilityMode.CHANGE);

    private final String key;
    private final CapabilityMode mode;

    CapabilityKey(String key, CapabilityMode mode) {
        this.key = key;
        this.mode = mode;
    }

    public String key() {
        return key;
    }

    public CapabilityMode mode() {
        return mode;
    }

    /** 精确匹配；大小写或空白变体不是能力。 */
    public static Optional<CapabilityKey> fromKey(String key) {
        Objects.requireNonNull(key, "key");
        return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
    }
}
