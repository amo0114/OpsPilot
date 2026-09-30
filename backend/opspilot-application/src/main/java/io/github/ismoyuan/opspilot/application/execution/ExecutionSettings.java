package io.github.ismoyuan.opspilot.application.execution;

import java.time.Duration;
import java.util.Objects;

/**
 * 写操作执行设置（07 §88）。maxReconciliationAttempts 在创建 Execution 时快照（04 §45，默认 3），调整不改已创建执行的上限；
 * 有界只读核对的尝试间隔、单次 inspect 超时与总期限（默认 5 秒、5 秒、60 秒，04 §82）由核对服务读取，总期限在首次进入核对时冻结为
 * reconciliation_deadline_at，此后调整不改已冻结的期限。
 */
public record ExecutionSettings(
        int maxReconciliationAttempts,
        Duration reconciliationInterval,
        Duration reconciliationTimeout,
        Duration reconciliationMaxDuration) {

    public ExecutionSettings {
        if (maxReconciliationAttempts < 1) {
            throw new IllegalArgumentException("maxReconciliationAttempts must be positive");
        }
        requirePositive(reconciliationInterval, "reconciliationInterval");
        requirePositive(reconciliationTimeout, "reconciliationTimeout");
        requirePositive(reconciliationMaxDuration, "reconciliationMaxDuration");
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
