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
public record PrometheusResourceBindingV1(Map<String, String> labels, Map<String, PrometheusMetricBindingV1> metrics)
        implements ResourceSelector {

    public static final String SCHEMA_NAME = "prometheus.resource.binding";
    public static final int SCHEMA_VERSION = 1;

    /** 稳定语义指标名，如 http.request.latency.p99、http.request.error_rate。 */
    private static final Pattern METRIC_KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*");

    /**
     * 与 AI 协议 v1 的 metricKey 上限一致（contracts/ai-runtime/v1）：超长的键无法向 AI 描述，配置阶段即拒绝，使该绑定按选择器非法处理，
     * 而不是在构造调查上下文时失败（B13-R1）。
     */
    public static final int MAX_METRIC_KEY_LENGTH = 128;

    public PrometheusResourceBindingV1 {
        labels = BindingLabels.requireValid(labels);
        Objects.requireNonNull(metrics, "metrics");
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("metrics must not be empty");
        }
        metrics.forEach((metricKey, binding) -> {
            if (metricKey == null
                    || metricKey.length() > MAX_METRIC_KEY_LENGTH
                    || !METRIC_KEY.matcher(metricKey).matches()) {
                throw new IllegalArgumentException("metrics contains an invalid metric key");
            }
            Objects.requireNonNull(binding, "metrics binding");
        });
        metrics = Map.copyOf(metrics);
    }
}
