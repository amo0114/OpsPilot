package io.github.ismoyuan.opspilot.application.capability.sanitize;

import io.github.ismoyuan.opspilot.application.capability.result.CapabilityResult;
import java.util.Objects;

/**
 * 已经过 {@link Sanitizer#sanitizeResult(CapabilityResult)} 的结构化结果。只能由 Sanitizer 产生；response_payload 编码与 ObservationExtractor
 * 只接受本类型（06 §31：Sanitizer 在 Observation 之前，CAP-INV-007）。
 */
public final class SanitizedResult {

    private final CapabilityResult value;

    SanitizedResult(CapabilityResult value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public CapabilityResult value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SanitizedResult result && value.equals(result.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "SanitizedResult[" + value.resultSchema().name() + "]";
    }
}
