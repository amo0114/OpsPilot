package io.github.ismoyuan.opspilot.application.capability.metrics;

/**
 * metrics.query 统计设置（06 §43：趋势由 Java 确定）。
 *
 * @param trendThreshold 窗口后半段与前半段平均值的相对变化超过该比例才判为 INCREASING/DECREASING，默认 0.1
 */
public record MetricsSettings(double trendThreshold) {

    public static final double DEFAULT_TREND_THRESHOLD = 0.1;

    public MetricsSettings {
        if (!(trendThreshold > 0 && trendThreshold < 1)) {
            throw new IllegalArgumentException("trend threshold must be within (0, 1)");
        }
    }
}
