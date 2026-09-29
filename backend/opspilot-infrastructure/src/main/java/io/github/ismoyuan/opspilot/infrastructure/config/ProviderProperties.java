package io.github.ismoyuan.opspilot.infrastructure.config;

import io.github.ismoyuan.opspilot.application.capability.logs.LogsSettings;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricsSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OBSERVE Provider 的配置（06 §43、§53、§125，均为配置值；未配置取默认）：
 *
 * <ul>
 *   <li>{@code opspilot.capability.providers.max-response-bytes}：单次 Provider 响应体上限，默认 8 MiB，超过记 RESULT_TOO_LARGE；
 *   <li>{@code ...prometheus.min-step} / {@code max-points}：query_range 步长下限（默认 15s）与每个窗口的点数上限（默认 240）；
 *   <li>{@code ...metrics.trend-threshold}：窗口内趋势判定的相对变化阈值，默认 0.1；
 *   <li>{@code ...logs.raw-match-limit / max-patterns / samples-per-pattern / ai-context-patterns / max-line-length}：
 *       默认 500 / 10 / 2 / 5 / 2000（06 §53、§122）。
 * </ul>
 */
@ConfigurationProperties("opspilot.capability.providers")
public record ProviderProperties(Long maxResponseBytes, Prometheus prometheus, Metrics metrics, Logs logs) {

    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
    public static final Duration DEFAULT_MIN_STEP = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_POINTS = 240;

    public record Prometheus(Duration minStep, Integer maxPoints) {}

    public record Metrics(Double trendThreshold) {}

    public record Logs(
            Integer rawMatchLimit,
            Integer maxPatterns,
            Integer samplesPerPattern,
            Integer aiContextPatterns,
            Integer maxLineLength) {}

    public ProviderProperties {
        if (maxResponseBytes != null && maxResponseBytes <= 0) {
            throw new IllegalArgumentException("max response bytes must be positive");
        }
        if (prometheus != null) {
            if (prometheus.minStep() != null && !prometheus.minStep().isPositive()) {
                throw new IllegalArgumentException("prometheus min step must be positive");
            }
            if (prometheus.maxPoints() != null && prometheus.maxPoints() < 2) {
                throw new IllegalArgumentException("prometheus max points must be at least 2");
            }
        }
    }

    public long responseLimit() {
        return maxResponseBytes == null ? DEFAULT_MAX_RESPONSE_BYTES : maxResponseBytes;
    }

    public Duration prometheusMinStep() {
        return prometheus == null || prometheus.minStep() == null ? DEFAULT_MIN_STEP : prometheus.minStep();
    }

    public int prometheusMaxPoints() {
        return prometheus == null || prometheus.maxPoints() == null ? DEFAULT_MAX_POINTS : prometheus.maxPoints();
    }

    public LogsSettings logsSettings() {
        LogsSettings defaults = LogsSettings.DEFAULTS;
        if (logs == null) {
            return defaults;
        }
        return new LogsSettings(
                or(logs.rawMatchLimit(), defaults.rawMatchLimit()),
                or(logs.maxPatterns(), defaults.maxPatterns()),
                or(logs.samplesPerPattern(), defaults.samplesPerPattern()),
                or(logs.aiContextPatterns(), defaults.aiContextPatterns()),
                or(logs.maxLineLength(), defaults.maxLineLength()));
    }

    private static int or(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    public MetricsSettings metricsSettings() {
        return new MetricsSettings(
                metrics == null || metrics.trendThreshold() == null
                        ? MetricsSettings.DEFAULT_TREND_THRESHOLD
                        : metrics.trendThreshold());
    }
}
