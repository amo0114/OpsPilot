package io.github.ismoyuan.opspilot.application.capability.result;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 实际查询的半开时间范围 [start, end)。 */
public record TimeRange(Instant start, Instant end) {

    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end");
        }
    }

    /** 半开区间：start 属于范围，end 不属于。 */
    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    public Duration length() {
        return Duration.between(start, end);
    }
}
