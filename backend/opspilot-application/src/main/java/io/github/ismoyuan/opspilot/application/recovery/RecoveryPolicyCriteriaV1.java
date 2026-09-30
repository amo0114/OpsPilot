package io.github.ismoyuan.opspilot.application.recovery;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * recovery.policy.criteria / 1（06 §113、07 §97）：非空、有序、逐项串行的 Criteria，载荷内自带 schemaName/schemaVersion。
 * 只接受 schemaVersion=1，未知字段与版本拒绝，不以 JsonNode 猜字段。
 *
 * <p>结构保证：criterionKey 在策略内唯一；至少一项 required（全部可选的策略不能判定恢复，06 §116）；按顺序串行时各项最短采样
 * 间隔之和不超过 maxDurationSeconds，否则该策略永远无法在期限内完成采样。
 *
 * @param maxDurationSeconds Verification 创建时冻结 deadline_at = created_at + maxDurationSeconds（04 §80）
 * @param maxSampleAgeSeconds 过期样本不能支持 TRUE 或 FALSE
 */
public record RecoveryPolicyCriteriaV1(
        String schemaName,
        int schemaVersion,
        int maxDurationSeconds,
        int maxSampleAgeSeconds,
        List<RecoveryCriterionV1> criteria) {

    public static final String SCHEMA_NAME = "recovery.policy.criteria";
    public static final int SCHEMA_VERSION = 1;

    public RecoveryPolicyCriteriaV1 {
        if (!SCHEMA_NAME.equals(schemaName)) {
            throw RecoveryChecks.invalid("schemaName");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw RecoveryChecks.invalid("schemaVersion");
        }
        RecoveryChecks.positive("maxDurationSeconds", maxDurationSeconds);
        RecoveryChecks.positive("maxSampleAgeSeconds", maxSampleAgeSeconds);
        RecoveryChecks.required("criteria", criteria);
        if (criteria.isEmpty()) {
            throw RecoveryChecks.invalid("criteria");
        }
        Set<String> keys = new HashSet<>();
        long span = 0;
        boolean anyRequired = false;
        for (RecoveryCriterionV1 criterion : criteria) {
            RecoveryChecks.required("criteria", criterion);
            if (!keys.add(criterion.criterionKey())) {
                throw RecoveryChecks.invalid("criterionKey");
            }
            span += criterion.sampling().minimumSpanSeconds();
            anyRequired |= criterion.required();
        }
        if (!anyRequired) {
            throw RecoveryChecks.invalid("required");
        }
        if (span > maxDurationSeconds) {
            throw RecoveryChecks.invalid("maxDurationSeconds");
        }
        criteria = List.copyOf(criteria);
    }

    public static RecoveryPolicyCriteriaV1 of(
            int maxDurationSeconds, int maxSampleAgeSeconds, List<RecoveryCriterionV1> criteria) {
        return new RecoveryPolicyCriteriaV1(
                SCHEMA_NAME, SCHEMA_VERSION, maxDurationSeconds, maxSampleAgeSeconds, criteria);
    }
}
