package io.github.ismoyuan.opspilot.infrastructure.config;

import io.github.ismoyuan.opspilot.application.execution.ExecutionSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 写操作执行配置（07 §88）：{@code opspilot.execution.max-reconciliation-attempts}，默认 3，在创建 Execution 时快照（04 §45）。
 * 核对间隔、单次超时与总期限随 TASK-072 加入。
 */
@ConfigurationProperties("opspilot.execution")
public record ExecutionProperties(Integer maxReconciliationAttempts) {

    public static final int DEFAULT_MAX_RECONCILIATION_ATTEMPTS = 3;

    public ExecutionSettings settings() {
        return new ExecutionSettings(
                maxReconciliationAttempts == null ? DEFAULT_MAX_RECONCILIATION_ATTEMPTS : maxReconciliationAttempts);
    }
}
