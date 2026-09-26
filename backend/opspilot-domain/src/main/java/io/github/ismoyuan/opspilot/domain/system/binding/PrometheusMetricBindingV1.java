package io.github.ismoyuan.opspilot.domain.system.binding;

import java.util.Objects;

/**
 * 一个 MetricKey 在 Prometheus 中的受信查询方式（06 §38、§40）；模板来自运维配置，AI 只能引用 MetricKey。
 *
 * @param queryTemplate 受信 PromQL 模板，占位符与单位换算由 metrics.query Provider（TASK-052）解释
 * @param unit 结果单位，如 ms
 */
public record PrometheusMetricBindingV1(String queryTemplate, String unit) {

    public PrometheusMetricBindingV1 {
        Objects.requireNonNull(queryTemplate, "queryTemplate");
        Objects.requireNonNull(unit, "unit");
        if (queryTemplate.isBlank()) {
            throw new IllegalArgumentException("queryTemplate must not be blank");
        }
        if (unit.isBlank()) {
            throw new IllegalArgumentException("unit must not be blank");
        }
    }
}
