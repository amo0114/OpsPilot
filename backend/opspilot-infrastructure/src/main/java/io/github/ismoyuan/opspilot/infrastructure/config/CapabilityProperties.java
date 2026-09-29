package io.github.ismoyuan.opspilot.infrastructure.config;

import io.github.ismoyuan.opspilot.application.capability.CapabilityGuardSettings;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Capability 配置：执行超时（06 §125：均为配置值，未配置的项取 {@link CapabilityRegistry#DEFAULT_TIMEOUTS}，例如
 * {@code opspilot.capability.timeouts.cache-inspect=5s}）与调查 Duplicate Guard 保护窗口（06 §124，默认 30 秒，
 * {@code opspilot.capability.duplicate-window}）、脱敏选项（06 §54：{@code opspilot.capability.sanitizer.redact-emails}，
 * 默认 true；凭据清理不可关闭）与已脱敏原始结果目录（07 §94～§95：{@code opspilot.capability.raw-result-directory}，
 * 环境变量 OPSPILOT_CAPABILITY_RAW_RESULT_DIRECTORY；默认 {@code ~/.opspilot/raw-results}，不写入仓库目录）。
 */
@ConfigurationProperties("opspilot.capability")
public record CapabilityProperties(
        Timeouts timeouts, Duration duplicateWindow, SanitizerOptions sanitizer, Path rawResultDirectory) {

    public static final Duration DEFAULT_DUPLICATE_WINDOW = Duration.ofSeconds(30);

    public record Timeouts(
            Duration metricsQuery,
            Duration logsSearch,
            Duration cacheInspect,
            Duration databaseInspect,
            Duration queueInspect,
            Duration serviceInspect,
            Duration serviceRestart) {}

    public record SanitizerOptions(Boolean redactEmails) {}

    public Path rawResultRoot() {
        return rawResultDirectory != null
                ? rawResultDirectory
                : Path.of(System.getProperty("user.home"), ".opspilot", "raw-results");
    }

    public SanitizerSettings sanitizerSettings() {
        return new SanitizerSettings(sanitizer == null || sanitizer.redactEmails() == null || sanitizer.redactEmails());
    }

    public CapabilityGuardSettings guardSettings() {
        return new CapabilityGuardSettings(duplicateWindow == null ? DEFAULT_DUPLICATE_WINDOW : duplicateWindow);
    }

    public Map<CapabilityKey, Duration> timeoutsByKey() {
        Map<CapabilityKey, Duration> result = new EnumMap<>(CapabilityRegistry.DEFAULT_TIMEOUTS);
        if (timeouts != null) {
            override(result, CapabilityKey.METRICS_QUERY, timeouts.metricsQuery());
            override(result, CapabilityKey.LOGS_SEARCH, timeouts.logsSearch());
            override(result, CapabilityKey.CACHE_INSPECT, timeouts.cacheInspect());
            override(result, CapabilityKey.DATABASE_INSPECT, timeouts.databaseInspect());
            override(result, CapabilityKey.QUEUE_INSPECT, timeouts.queueInspect());
            override(result, CapabilityKey.SERVICE_INSPECT, timeouts.serviceInspect());
            override(result, CapabilityKey.SERVICE_RESTART, timeouts.serviceRestart());
        }
        return result;
    }

    private static void override(Map<CapabilityKey, Duration> result, CapabilityKey key, Duration value) {
        if (value != null) {
            result.put(key, value);
        }
    }
}
