package io.github.ismoyuan.opspilot.application.execution;

import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import java.time.Instant;
import java.util.Optional;

/** ActionExecution 持久化端口（04 §45～§47）；状态推进由 Worker 与完成事务按条件更新完成（TASK-071 起）。 */
public interface ActionExecutionRepository {

    /**
     * 插入 PENDING Execution；同一 Action 的第二次插入违反唯一约束，不以换键重试（04 §47）。
     *
     * @return 新 Execution id
     */
    long insertPending(NewActionExecution execution);

    Optional<ExecutionRef> findByActionId(long remediationActionId);

    /** 一次 Execution 的身份与当前状态。 */
    record ExecutionRef(long executionId, ActionExecutionStatus status) {}

    /** 批准事务创建的 PENDING 行；快照与执行上下文已按 Schema 编码。 */
    record NewActionExecution(
            long remediationActionId,
            long approvalRequestId,
            String idempotencyKey,
            String executorKey,
            long recoveryPolicyId,
            int recoveryPolicyVersion,
            String recoveryPolicySnapshot,
            String executionContextSchemaName,
            int executionContextSchemaVersion,
            String executionContextPayload,
            int maxReconciliationAttempts,
            String correlationId,
            Instant createdAt) {}
}
