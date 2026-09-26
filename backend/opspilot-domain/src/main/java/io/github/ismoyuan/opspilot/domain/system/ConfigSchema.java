package io.github.ismoyuan.opspilot.domain.system;

import java.util.Objects;

/** DataSourceConnection 配置载荷的结构标识（04 §9、§69）；载荷只能按此 name/version 经强类型 Codec 解释。 */
public record ConfigSchema(String name, int version) {

    public ConfigSchema {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("config schema name must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("config schema version must be >= 1");
        }
    }
}
