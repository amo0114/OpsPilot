package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import java.time.Instant;
import java.util.Optional;

/** 页面读取的 Verification 投影（07 §24）；只读，不改变任何状态。 */
public interface RecoveryVerificationQuery {

    /** 该 Incident 编号最大的一次 Verification。 */
    Optional<VerificationSnapshot> findLatest(long incidentId);

    /**
     * @param actionExecutionId 外部处理后的 verify-recovery 为空（04 §54）
     * @param policySnapshot 创建时冻结的 recovery.policy.snapshot / 1
     * @param resultSummary 终态才有
     * @param resultPayload 终态才有：recovery.verification.result / 1
     */
    record VerificationSnapshot(
            long id,
            int verificationNo,
            RecoveryVerificationStatus status,
            Long actionExecutionId,
            String resourceKey,
            String resourceName,
            String policySnapshot,
            String resultSummary,
            String resultPayload,
            Instant deadlineAt,
            Instant startedAt,
            Instant finishedAt) {}
}
