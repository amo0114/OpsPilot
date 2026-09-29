package io.github.ismoyuan.opspilot.application.capability.metrics;

import java.time.Instant;
import java.util.Objects;

/** Provider 返回的一个时序点；value 可为 NaN 或无穷（如无流量时的分位数），统计时剔除。 */
public record MetricPoint(Instant timestamp, double value) {

    public MetricPoint {
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
