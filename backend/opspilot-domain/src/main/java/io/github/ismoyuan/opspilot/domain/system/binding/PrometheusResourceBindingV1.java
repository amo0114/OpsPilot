package io.github.ismoyuan.opspilot.domain.system.binding;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 资源在 Prometheus 中的定位与其实际拥有的指标（06 §19、§39～§40）。资源只向 AI 暴露这里声明的 MetricKey。
 *
 * @param labels 定位资源的标签，至少一个
 * @param metrics MetricKey → 受信查询绑定，至少一个
 */
public record PrometheusResourceBindingV1(Map<String, String> labels, Map<String, PrometheusMetricBindingV1> metrics) {

    public static final String SCHEMA_NAME = "prometheus.resource.binding";
    public static final int SCHEMA_VERSION = 1;

    /** 稳定语义指标名，如 http.request.latency.p99、http.request.error_rate。 */
    private static final Pattern METRIC_KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*");

    public PrometheusResourceBindingV1 {
        labels = BindingLabels.requireValid(labels);
        Objects.requireNonNull(metrics, "metrics");
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("metrics must not be empty");
        }
        metrics.forEach((metricKey, binding) -> {
            if (metricKey == null || !METRIC_KEY.matcher(metricKey).matches()) {
                throw new IllegalArgumentException("metrics contains an invalid metric key");
            }
            Objects.requireNonNull(binding, "metrics binding");
        });
        metrics = Map.copyOf(metrics);
    }
}
