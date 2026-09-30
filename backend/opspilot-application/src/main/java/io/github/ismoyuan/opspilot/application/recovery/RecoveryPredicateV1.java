package io.github.ismoyuan.opspilot.application.recovery;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 受控恢复谓词（06 §113）：按 type 判别的三种固定形式，字段只能是所在 Criterion 能力的注册投影（{@link RecoveryField}），
 * 与字段类型的相容性由 {@link RecoveryCriterionV1} 校验。不支持 SpEL、脚本、SQL 或任意 JSONPath。
 * 多样本时 FIELD_EQUALS / NUMERIC_COMPARE 要求全部有效样本满足；求值属 TASK-077。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RecoveryPredicateV1.FieldEquals.class, name = "FIELD_EQUALS"),
    @JsonSubTypes.Type(value = RecoveryPredicateV1.NumericCompare.class, name = "NUMERIC_COMPARE"),
    @JsonSubTypes.Type(value = RecoveryPredicateV1.MonotonicTrend.class, name = "MONOTONIC_TREND")
})
public sealed interface RecoveryPredicateV1 {

    /** 注册投影的字段名。 */
    String field();

    /** 文本（枚举）字段等于给定取值，取值须是结果 Schema 的枚举名，如 runtimeState == RUNNING。 */
    record FieldEquals(String field, String value) implements RecoveryPredicateV1 {

        public FieldEquals {
            RecoveryChecks.required("predicate.field", field);
            RecoveryChecks.required("predicate.value", value);
        }
    }

    /** 数值字段与有限阈值比较，如 lag <= 20。 */
    record NumericCompare(String field, ComparisonOperator operator, Double value) implements RecoveryPredicateV1 {

        public NumericCompare {
            RecoveryChecks.required("predicate.field", field);
            RecoveryChecks.required("predicate.operator", operator);
            RecoveryChecks.finite("predicate.value", value);
        }
    }

    /**
     * 数值序列趋势。无健康区间时 DECREASING 表示有效序列非增且至少一次严格下降，INCREASING 对称。
     * 给出 healthyThreshold（只用于 DECREASING）时采用健康区间 [0, healthyThreshold] 语义：已在区间内并保持即通过，
     * 区间外阶段允许非单调噪声，不把严格下降作为唯一成功（06 §113、09 §75）。
     *
     * @param healthyThreshold 可空；给出时必须同时给出 requireFinalHealthy
     * @param requireFinalHealthy 可空；与 healthyThreshold 同时出现
     */
    record MonotonicTrend(String field, TrendDirection direction, Double healthyThreshold, Boolean requireFinalHealthy)
            implements RecoveryPredicateV1 {

        public MonotonicTrend {
            RecoveryChecks.required("predicate.field", field);
            RecoveryChecks.required("predicate.direction", direction);
            if ((healthyThreshold == null) != (requireFinalHealthy == null)) {
                throw RecoveryChecks.invalid("predicate.requireFinalHealthy");
            }
            if (healthyThreshold != null
                    && (direction != TrendDirection.DECREASING
                            || RecoveryChecks.finite("predicate.healthyThreshold", healthyThreshold) < 0)) {
                throw RecoveryChecks.invalid("predicate.healthyThreshold");
            }
        }
    }

    enum ComparisonOperator {
        LT,
        LTE,
        GT,
        GTE
    }

    enum TrendDirection {
        DECREASING,
        INCREASING
    }
}
