package io.github.ismoyuan.opspilot.infrastructure.config;

import io.github.ismoyuan.opspilot.application.execution.ExecutionSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 写操作执行配置（07 §88）：{@code opspilot.execution.max-reconciliation-attempts}（默认 3，在创建 Execution 时快照，04 §45）、
 * {@code reconciliation-interval-seconds}（5）、{@code reconciliation-timeout-seconds}（5）、
 * {@code reconciliation-max-duration-seconds}（60）。
 */
@ConfigurationProperties("opspilot.execution")
public record ExecutionProperties(
        Integer maxReconciliationAttempts,
        Integer reconciliationIntervalSeconds,
        Integer reconciliationTimeoutSeconds,
        Integer reconciliationMaxDurationSeconds) {

    public static final int DEFAULT_MAX_RECONCILIATION_ATTEMPTS = 3;
    public static final int DEFAULT_RECONCILIATION_INTERVAL_SECONDS = 5;
    public static final int DEFAULT_RECONCILIATION_TIMEOUT_SECONDS = 5;
    public static final int DEFAULT_RECONCILIATION_MAX_DURATION_SECONDS = 60;

    public ExecutionSettings settings() {
        return new ExecutionSettings(
                maxReconciliationAttempts == null ? DEFAULT_MAX_RECONCILIATION_ATTEMPTS : maxReconciliationAttempts,
                seconds(reconciliationIntervalSeconds, DEFAULT_RECONCILIATION_INTERVAL_SECONDS),
                seconds(reconciliationTimeoutSeconds, DEFAULT_RECONCILIATION_TIMEOUT_SECONDS),
                seconds(reconciliationMaxDurationSeconds, DEFAULT_RECONCILIATION_MAX_DURATION_SECONDS));
    }

    private static Duration seconds(Integer configured, int fallback) {
        return Duration.ofSeconds(configured == null ? fallback : configured);
    }
}
