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

    /** Worker 读取的执行记录，含所属方案与 Incident。 */
    Optional<ExecutionRecord> findById(long executionId);

    /**
     * 唯一的 CHANGE 准入（04 §82）：PENDING → RUNNING，同时保存已解析容器身份的执行上下文与 started_at。
     *
     * @return 条件更新是否由本次获胜
     */
    boolean markRunning(long executionId, long expectedVersion, String contextPayload, Instant startedAt);

    /** RUNNING → SUCCEEDED 并保存类型化结果。 */
    boolean markSucceeded(
            long executionId,
            long expectedVersion,
            String resultSchemaName,
            int resultSchemaVersion,
            String resultPayload,
            Instant finishedAt);

    /** {@code from}（PENDING 或 RUNNING）→ FAILED；message 必须是不含端点或响应内容的固定文案。 */
    boolean markFailed(
            long executionId,
            ActionExecutionStatus from,
            long expectedVersion,
            String errorCode,
            String errorMessage,
            Instant finishedAt);

    /** 方案 ACTIVE → EXECUTED：已发生执行尝试，不表示成功或已恢复（04 §38）。 */
    void markPlanExecuted(long planId, Instant at);

    /** 方案 ACTIVE → CANCELLED：准入前失败，没有发生执行尝试。 */
    void markPlanCancelled(long planId, Instant at);

    /**
     * @param startedAt RUNNING 准入时写入；PENDING 为空
     */
    record ExecutionRecord(
            long id,
            ActionExecutionStatus status,
            long lockVersion,
            long remediationActionId,
            long planId,
            long incidentId,
            String executionContextPayload,
            Instant startedAt) {}

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
