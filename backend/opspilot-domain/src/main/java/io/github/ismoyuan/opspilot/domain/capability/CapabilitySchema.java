package io.github.ismoyuan.opspilot.domain.capability;

import java.util.Objects;

/** Capability 请求或结果载荷的结构标识（06 §118），如 metrics.query.request / 1。 */
public record CapabilitySchema(String name, int version) {

    public CapabilitySchema {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("schema name must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("schema version must be >= 1");
        }
    }

    /** V0.1 命名：{capabilityKey}.request / 1 与 {capabilityKey}.result / 1（06 §118）。 */
    static CapabilitySchema request(CapabilityKey key) {
        return new CapabilitySchema(key.key() + ".request", 1);
    }

    static CapabilitySchema result(CapabilityKey key) {
        return new CapabilitySchema(key.key() + ".result", 1);
    }
}
