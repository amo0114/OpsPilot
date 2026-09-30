package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.ComparisonOperator;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.TrendDirection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 恢复谓词与整体结果的唯一实现（08 TASK-077、06 §113、§116）：Runner、结果载荷与界面都只使用这里的结论，不另维护优先级。
 * 只支持三种受控谓词，没有 SpEL、脚本、SQL 或 JSONPath。
 *
 * <p>样本先逐槽位（1..sampleCount）分类：缺失、调用失败或未完成、值未知（含 NaN、趋势健康区间下的负数）、在冻结的 deadline 之后
 * 才取得、过期（超过 maxSampleAgeSeconds）都不可用；可用样本按序号排列，相邻可用样本的真实采样间隔超过 maxGapSeconds 即不连续。
 * <ul>
 *   <li>FALSE 只来自可用样本本身的明确违反（任何未过期的有效样本不满足 FIELD_EQUALS / NUMERIC_COMPARE，趋势中可用样本之间的反向
 *       变化或进入健康区间后反弹超界），或完整且连续的序列仍不满足趋势要求。
 *   <li>TRUE 需要全部槽位可用、连续且满足谓词。
 *   <li>其余为 UNKNOWN，原因取序号最小的不可用槽位（或不连续）。UNKNOWN 永远不覆盖明确 FALSE。
 * </ul>
 */
public final class RecoveryPredicateEvaluator {

    private RecoveryPredicateEvaluator() {}

    /**
     * @param samples 该 Criterion 已持久化的样本（任意顺序；超出 sampleCount 的序号不参与）
     * @param maxSampleAge 快照的 maxSampleAgeSeconds
     * @param deadline Verification 创建时冻结的 deadline_at；之后取得的样本不可用
     * @param now 求值时刻，用于样本时效
     */
    public static CriterionEvaluation evaluate(
            RecoveryCriterionV1 criterion,
            List<RecoverySample> samples,
            Duration maxSampleAge,
            Instant deadline,
            Instant now) {
        Objects.requireNonNull(criterion, "criterion");
        Objects.requireNonNull(maxSampleAge, "maxSampleAge");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(now, "now");
        RecoveryPredicateV1 predicate = criterion.predicate();
        boolean nonNegative =
                predicate instanceof RecoveryPredicateV1.MonotonicTrend trend && trend.healthyThreshold() != null;
        Series series = Series.of(criterion.sampling(), samples, maxSampleAge, deadline, now, nonNegative);
        return switch (predicate) {
            case RecoveryPredicateV1.FieldEquals equals ->
                everySample(
                        series,
                        value -> value instanceof ProjectedValue.Text text
                                ? text.value().equals(equals.value())
                                : null);
            case RecoveryPredicateV1.NumericCompare compare ->
                everySample(
                        series,
                        value -> value instanceof ProjectedValue.Number number
                                ? compares(number.value(), compare.operator(), compare.value())
                                : null);
            case RecoveryPredicateV1.MonotonicTrend trend ->
                trend.healthyThreshold() == null ? trend(series, trend.direction()) : healthyTrend(series, trend);
        };
    }

    /**
     * required 三值合取（06 §116）：任一 FALSE → FAILED；否则任一 UNKNOWN → INCONCLUSIVE；否则 PASSED。可选项不影响结果。
     *
     * @throws IllegalArgumentException 没有 required 检查（策略结构已禁止）
     */
    public static RecoveryOutcome overall(List<Check> checks) {
        boolean anyRequired = false;
        boolean anyUnknown = false;
        for (Check check : checks) {
            if (!check.required()) {
                continue;
            }
            anyRequired = true;
            if (check.result() == CriterionResult.FALSE) {
                return RecoveryOutcome.FAILED;
            }
            anyUnknown |= check.result() == CriterionResult.UNKNOWN;
        }
        if (!anyRequired) {
            throw new IllegalArgumentException("a recovery verification needs at least one required check");
        }
        return anyUnknown ? RecoveryOutcome.INCONCLUSIVE : RecoveryOutcome.PASSED;
    }

    /** 参与合取的一项：是否 required 与其三值结果。 */
    public record Check(boolean required, CriterionResult result) {

        public Check {
            Objects.requireNonNull(result, "result");
        }
    }

    /** 多样本时 FIELD_EQUALS / NUMERIC_COMPARE 要求全部有效样本满足（06 §113）。 */
    private static CriterionEvaluation everySample(
            Series series, java.util.function.Function<ProjectedValue, Boolean> satisfies) {
        for (Point point : series.usable()) {
            Boolean ok = satisfies.apply(point.value());
            if (ok == null) {
                // 字段与谓词类型不符（结构校验已排除），不当作数据
                return CriterionEvaluation.unknown(CriterionReason.VALUE_UNKNOWN);
            }
            if (!ok) {
                return CriterionEvaluation.violated();
            }
        }
        return series.complete() ? CriterionEvaluation.satisfied() : CriterionEvaluation.unknown(series.gapReason());
    }

    /** 无健康区间：DECREASING 为非增且至少一次严格下降，INCREASING 对称；不能仅凭部分点宣称趋势。 */
    private static CriterionEvaluation trend(Series series, TrendDirection direction) {
        List<Double> values = series.numbers();
        if (values == null) {
            return CriterionEvaluation.unknown(CriterionReason.VALUE_UNKNOWN);
        }
        for (int i = 0; i < values.size(); i++) {
            for (int j = i + 1; j < values.size(); j++) {
                if (direction == TrendDirection.DECREASING
                        ? values.get(j) > values.get(i)
                        : values.get(j) < values.get(i)) {
                    return CriterionEvaluation.violated();
                }
            }
        }
        if (!series.complete()) {
            return CriterionEvaluation.unknown(series.gapReason());
        }
        boolean moved = values.getFirst().compareTo(values.getLast()) != 0;
        return moved ? CriterionEvaluation.satisfied() : CriterionEvaluation.violated();
    }

    /**
     * S3 健康区间语义（06 §113、09 §75）：区间 [0, healthyThreshold]。已在区间内并保持即 TRUE；首点在区间外时要求进入并保持（区间外
     * 阶段允许非单调噪声）；进入后反弹超界即 FALSE（有效样本即可决定）；完整采样仍未进入为 FALSE。requireFinalHealthy=false 时，
     * 完整序列未进入但末点低于首点也视为总体下降。
     */
    private static CriterionEvaluation healthyTrend(Series series, RecoveryPredicateV1.MonotonicTrend trend) {
        List<Double> values = series.numbers();
        if (values == null) {
            return CriterionEvaluation.unknown(CriterionReason.VALUE_UNKNOWN);
        }
        double threshold = trend.healthyThreshold();
        boolean entered = false;
        for (double value : values) {
            if (value <= threshold) {
                entered = true;
            } else if (entered) {
                return CriterionEvaluation.violated();
            }
        }
        if (!series.complete()) {
            return CriterionEvaluation.unknown(series.gapReason());
        }
        if (entered) {
            return CriterionEvaluation.satisfied();
        }
        boolean decreased = values.getLast() < values.getFirst();
        return !trend.requireFinalHealthy() && decreased
                ? CriterionEvaluation.satisfied()
                : CriterionEvaluation.violated();
    }

    private static boolean compares(double actual, ComparisonOperator operator, double threshold) {
        return switch (operator) {
            case LT -> actual < threshold;
            case LTE -> actual <= threshold;
            case GT -> actual > threshold;
            case GTE -> actual >= threshold;
        };
    }

    /** 一个可用样本：未过期、成功且值已知。 */
    private record Point(int sampleIndex, Instant sampledAt, ProjectedValue value) {}

    /**
     * 按槽位分类后的样本序列。
     *
     * @param usable 可用样本，按序号升序
     * @param complete 全部槽位可用且相邻间隔不超过 maxGapSeconds
     * @param gapReason 不完整时的原因（序号最小的不可用槽位，或不连续）
     */
    private record Series(List<Point> usable, boolean complete, CriterionReason gapReason) {

        static Series of(
                RecoverySamplingV1 sampling,
                List<RecoverySample> samples,
                Duration maxSampleAge,
                Instant deadline,
                Instant now,
                boolean nonNegative) {
            Map<Integer, RecoverySample> bySlot = new HashMap<>();
            for (RecoverySample sample : samples) {
                if (sample.sampleIndex() <= sampling.sampleCount()) {
                    bySlot.put(sample.sampleIndex(), sample);
                }
            }
            List<Point> usable = new ArrayList<>();
            CriterionReason firstIssue = null;
            for (int slot = 1; slot <= sampling.sampleCount(); slot++) {
                CriterionReason issue = issue(bySlot.get(slot), maxSampleAge, deadline, now, nonNegative);
                if (issue == null) {
                    RecoverySample sample = bySlot.get(slot);
                    usable.add(new Point(slot, sample.sampledAt(), sample.value()));
                } else if (firstIssue == null) {
                    firstIssue = issue;
                }
            }
            if (firstIssue == null && sampling.maxGapSeconds() != null) {
                Duration maxGap = Duration.ofSeconds(sampling.maxGapSeconds());
                for (int i = 1; i < usable.size(); i++) {
                    if (Duration.between(
                                            usable.get(i - 1).sampledAt(),
                                            usable.get(i).sampledAt())
                                    .compareTo(maxGap)
                            > 0) {
                        firstIssue = CriterionReason.SAMPLE_GAP_EXCEEDED;
                        break;
                    }
                }
            }
            return new Series(List.copyOf(usable), firstIssue == null, firstIssue);
        }

        /** @return 不可用的原因；可用为空 */
        private static CriterionReason issue(
                RecoverySample sample, Duration maxSampleAge, Instant deadline, Instant now, boolean nonNegative) {
            if (sample == null) {
                return CriterionReason.INSUFFICIENT_SAMPLES;
            }
            if (sample.status() != RecoverySample.Status.SUCCEEDED) {
                return CriterionReason.SAMPLE_FAILED;
            }
            if (sample.value() instanceof ProjectedValue.Unknown
                    || (nonNegative && sample.value() instanceof ProjectedValue.Number number && number.value() < 0)) {
                return CriterionReason.VALUE_UNKNOWN;
            }
            if (sample.sampledAt().isAfter(deadline)) {
                return CriterionReason.SAMPLE_AFTER_DEADLINE;
            }
            if (sample.sampledAt().isAfter(now)
                    || Duration.between(sample.sampledAt(), now).compareTo(maxSampleAge) > 0) {
                return CriterionReason.SAMPLE_EXPIRED;
            }
            return null;
        }

        /** 可用样本的数值；出现非数值（类型不符）时为空。 */
        List<Double> numbers() {
            List<Double> numbers = new ArrayList<>();
            for (Point point : usable) {
                if (!(point.value() instanceof ProjectedValue.Number number)) {
                    return null;
                }
                numbers.add(number.value());
            }
            return numbers;
        }
    }
}
