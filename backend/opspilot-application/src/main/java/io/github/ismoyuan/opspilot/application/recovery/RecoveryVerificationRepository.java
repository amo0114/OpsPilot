package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import java.time.Instant;
import java.util.Optional;

/**
 * RecoveryVerification 持久化端口（04 §50～§54）：状态只按期望状态与版本条件推进，终态不覆盖。创建只插入 PENDING（TASK-080/081）。
 */
public interface RecoveryVerificationRepository {

    /**
     * 插入 PENDING Verification；同一 Execution 的第二次插入违反 uk_recovery_verification_execution（DB-INV-003）。
     *
     * @return 新 Verification id
     */
    long insertPending(NewRecoveryVerification verification);

    /** 该 Incident 下一个 verification_no（已有最大值＋1，从 1 开始）；调用方须持有 Incident 行锁。 */
    int nextVerificationNo(long incidentId);

    /** 该 Incident 是否有 PENDING 或 RUNNING 的 Verification。 */
    boolean existsActive(long incidentId);

    Optional<RecoveryVerificationRecord> findById(long verificationId);

    /** PENDING → RUNNING 并写 started_at。@return 条件更新是否由本次获胜 */
    boolean markRunning(long verificationId, long expectedVersion, Instant startedAt);

    /**
     * {@code from}（PENDING 或 RUNNING）→ 终态，写结果载荷、摘要与 finished_at。
     *
     * @return 条件更新是否由本次获胜
     */
    boolean markFinished(
            long verificationId,
            RecoveryVerificationStatus from,
            long expectedVersion,
            RecoveryVerificationStatus outcome,
            String resultSummary,
            String resultPayload,
            Instant finishedAt);

    /**
     * 创建时冻结的一次验证：策略身份与完整快照、所验证资源与期限。
     *
     * @param actionExecutionId 外部处理后的 verify-recovery 为空（04 §54）
     * @param deadlineAt 创建时间＋快照 maxDurationSeconds（04 §80）
     */
    record NewRecoveryVerification(
            long incidentId,
            Long actionExecutionId,
            long managedResourceId,
            long recoveryPolicyId,
            int recoveryPolicyVersion,
            String policySnapshot,
            int verificationNo,
            Instant deadlineAt,
            Instant createdAt) {}

    /**
     * @param policySnapshot recovery.policy.snapshot / 1 的 JSON，创建时冻结
     * @param deadlineAt 创建时冻结，重启不刷新
     * @param startedAt PENDING 为空
     */
    record RecoveryVerificationRecord(
            long id,
            long incidentId,
            int verificationNo,
            RecoveryVerificationStatus status,
            String policySnapshot,
            Instant deadlineAt,
            Instant startedAt,
            long lockVersion) {}
}
