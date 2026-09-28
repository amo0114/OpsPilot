package io.github.ismoyuan.opspilot.application.capability;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 一个左闭右开的查询时间范围 [start, end)，长度为正。 */
public record QueryWindow(Instant start, Instant end) {

    public QueryWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("window must have a positive length");
        }
    }

    public Duration length() {
        return Duration.between(start, end);
    }
}
