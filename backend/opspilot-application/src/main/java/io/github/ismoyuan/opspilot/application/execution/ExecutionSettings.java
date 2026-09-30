package io.github.ismoyuan.opspilot.application.execution;

/**
 * 写操作执行设置（07 §88）。maxReconciliationAttempts 在创建 Execution 时快照进行（04 §45，默认 3），调整不改已创建执行的上限；
 * 核对间隔、单次超时与总期限随 TASK-072 加入。
 */
public record ExecutionSettings(int maxReconciliationAttempts) {

    public ExecutionSettings {
        if (maxReconciliationAttempts < 1) {
            throw new IllegalArgumentException("maxReconciliationAttempts must be positive");
        }
    }
}
