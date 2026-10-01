package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import java.time.Instant;
import java.util.List;

/**
 * 故障详情的“恢复情况”（00 §26、05 §48、08 TASK-085）：Verification 整体业务状态与按快照顺序的每项检查分开表达——检查只用
 * TRUE/FALSE/UNKNOWN，整体只用 PENDING/RUNNING/PASSED/FAILED/INCONCLUSIVE。终态的检查结果与原因取自持久化结果载荷，不在查询时
 * 重新判定；进行中的检查尚无结论（result、reason 为空），只列出已登记的样本。所有已定义检查都会出现，未采样的检查样本为空，不补造。
 *
 * @param afterExecution 是否由一次已批准的 Execution 成功后发起（否则为外部处理后的 verify-recovery）
 * @param resultSummary 终态才有
 * @param startedAt PENDING 为空
 * @param finishedAt 终态才有
 */
public record RecoveryStatusView(
        int verificationNo,
        RecoveryVerificationStatus status,
        String resultSummary,
        String resourceKey,
        String resourceName,
        String policyKey,
        String policyName,
        int policyVersion,
        boolean afterExecution,
        Instant deadlineAt,
        Instant startedAt,
        Instant finishedAt,
        List<Check> checks) {

    public RecoveryStatusView {
        checks = List.copyOf(checks);
    }

    /**
     * @param result 进行中为空
     * @param reason 进行中为空；UNKNOWN 时说明原因（如 SAMPLE_FAILED、INSUFFICIENT_SAMPLES、NOT_EXECUTED）
     */
    public record Check(
            String criterionKey,
            String name,
            boolean required,
            CriterionResult result,
            CriterionReason reason,
            List<Sample> samples) {

        public Check {
            samples = List.copyOf(samples);
        }
    }

    /**
     * 一个已登记的样本槽位。
     *
     * @param sampledAt 成功样本的真实采样时间，其余为空
     * @param value 成功样本按注册投影取得的值（可能是 {@link ProjectedValue.Unknown}），其余为空
     * @param errorCode 失败样本的错误码，其余为空
     */
    public record Sample(
            int sampleIndex, RecoverySample.Status status, Instant sampledAt, ProjectedValue value, String errorCode) {}
}
