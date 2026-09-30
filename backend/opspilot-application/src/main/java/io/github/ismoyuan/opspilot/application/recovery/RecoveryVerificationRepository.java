package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import java.time.Instant;
import java.util.Optional;

/**
 * RecoveryVerification 持久化端口（04 §50～§54）：状态只按期望状态与版本条件推进，终态不覆盖。创建属 TASK-080/081。
 */
public interface RecoveryVerificationRepository {

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
