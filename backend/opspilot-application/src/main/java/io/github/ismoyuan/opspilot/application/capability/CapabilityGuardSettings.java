package io.github.ismoyuan.opspilot.application.capability;

import java.time.Duration;
import java.util.Objects;

/** 调查 Duplicate Guard 的保护窗口（06 §124、07 §57：默认 30 秒，可配置）。 */
public record CapabilityGuardSettings(Duration duplicateWindow) {

    public CapabilityGuardSettings {
        Objects.requireNonNull(duplicateWindow, "duplicateWindow");
        if (duplicateWindow.isNegative() || duplicateWindow.isZero()) {
            throw new IllegalArgumentException("duplicate window must be positive");
        }
    }
}
