package io.github.ismoyuan.opspilot.application.recovery;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * recovery.verification.result / 1（04 §50）：终态时按快照顺序记录每项检查的三值结果、原因与样本 Invocation 引用，以及整体结果。
 * overallResult 由 {@link RecoveryPredicateEvaluator#overall} 得出，result_summary 只是它的用户摘要。
 *
 * @param evaluatedAt 求值时刻（样本时效以此为准）
 */
public record RecoveryVerificationResultV1(
        String schemaName, int schemaVersion, RecoveryOutcome overallResult, Instant evaluatedAt, List<Check> checks) {

    public static final String SCHEMA_NAME = "recovery.verification.result";
    public static final int SCHEMA_VERSION = 1;

    public RecoveryVerificationResultV1 {
        if (!SCHEMA_NAME.equals(schemaName) || schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("recovery.verification.result / 1 expected");
        }
        Objects.requireNonNull(overallResult, "overallResult");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        if (checks.isEmpty()) {
            throw new IllegalArgumentException("checks must not be empty");
        }
    }

    public static RecoveryVerificationResultV1 of(RecoveryOutcome overall, Instant evaluatedAt, List<Check> checks) {
        return new RecoveryVerificationResultV1(SCHEMA_NAME, SCHEMA_VERSION, overall, evaluatedAt, checks);
    }

    /** 一项检查（快照顺序）。 */
    public record Check(
            String criterionKey,
            String name,
            boolean required,
            CriterionResult result,
            CriterionReason reason,
            List<Sample> samples) {

        public Check {
            Objects.requireNonNull(criterionKey, "criterionKey");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(reason, "reason");
            samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
        }
    }

    /**
     * 一个已登记的样本槽位。
     *
     * @param status SUCCEEDED / FAILED / RUNNING
     * @param sampledAt 成功样本的真实采样时间，其余为空
     */
    public record Sample(int sampleIndex, long invocationId, RecoverySample.Status status, Instant sampledAt) {

        public Sample {
            Objects.requireNonNull(status, "status");
        }
    }
}
