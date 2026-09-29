package io.github.ismoyuan.opspilot.application.capability.extract;

import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1.PreviousWindow;
import io.github.ismoyuan.opspilot.application.capability.result.TimeRange;
import io.github.ismoyuan.opspilot.application.capability.result.Trend;
import java.util.Objects;

/** metric.observation / 1（06 §44、§117）：一次 metrics.query 的真实统计；取值约束已由 MetricsQueryResultV1 保证。 */
public record MetricObservationV1(
        String metricKey,
        String unit,
        TimeRange window,
        int sampleCount,
        Double latest,
        Double min,
        Double max,
        Double average,
        PreviousWindow previousWindow,
        Double changePercent,
        Trend trend) {

    public static final String SCHEMA_NAME = "metric.observation";
    public static final int SCHEMA_VERSION = 1;

    public MetricObservationV1 {
        Objects.requireNonNull(metricKey, "metricKey");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(trend, "trend");
    }
}
