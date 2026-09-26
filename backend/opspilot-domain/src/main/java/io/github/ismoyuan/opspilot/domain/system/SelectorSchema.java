package io.github.ismoyuan.opspilot.domain.system;

import java.util.Objects;

/**
 * ResourceBinding 选择器载荷的结构标识（04 §10、§69）。载荷只能按此 name/version 经强类型 Codec 解释，
 * 不以 Map 形式在应用层传递（07 §96）。
 */
public record SelectorSchema(String name, int version) {

    public SelectorSchema {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("selector schema name must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("selector schema version must be >= 1");
        }
    }
}
