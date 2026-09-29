package io.github.ismoyuan.opspilot.application.capability.result;

import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import java.util.Objects;

/**
 * metrics.query.result / 1（06 §43）：一个 MetricKey 在准入时解析窗口内的真实样本统计。完整点序列不在此，可随 raw_result_ref 保存（06 §45）。
 *
 * <p>构造即保证不编造（06 §42、08 TASK-051）：没有样本时统计值全部为空；只有 1 个样本时各统计值相等；样本少于 2 个时趋势只能是 UNKNOWN；
 * 只有当前与前一窗口都有样本且前一窗口平均值不为 0 时才允许 changePercent；前一窗口必须与当前窗口等长紧邻。
 *
 * @param previousWindow 未要求比较时为空
 * @param changePercent 总是由 (average − previous.average) / previous.average × 100 派生（可比较时才有值）；Provider 可省略，
 *     给出的值须与两侧平均值相符（误差不超过 0.05 个百分点，对应一位小数），不能与统计矛盾（B15-R1 同类一致性）
 */
public record MetricsQueryResultV1(
        String metricKey,
        String unit,
        TimeRange window,
        int sampleCount,
        Double latest,
        Double min,
        Double max,
        Double average,
        PreviousWindow previousWindow,
        Double changePercent,
        Trend trend)
        implements CapabilityResult {

    public static final String SCHEMA_NAME = "metrics.query.result";
    public static final int SCHEMA_VERSION = 1;

    static final double CHANGE_PERCENT_TOLERANCE = 0.05;

    /** 前一比较窗口的真实统计；没有样本时 average 为空，不当 0。 */
    public record PreviousWindow(TimeRange window, int sampleCount, Double average) {

        public PreviousWindow {
            Objects.requireNonNull(window, "previousWindow.window");
            ResultChecks.nonNegative("previousWindow.sampleCount", sampleCount);
            ResultChecks.optionalFinite("previousWindow.average", average);
            if ((sampleCount == 0) != (average == null)) {
                throw new IllegalArgumentException("previousWindow.average must be present exactly when sampled");
            }
        }
    }

    public MetricsQueryResultV1 {
        ResultChecks.text("metricKey", metricKey);
        ResultChecks.text("unit", unit);
        Objects.requireNonNull(window, "window");
        ResultChecks.nonNegative("sampleCount", sampleCount);
        ResultChecks.optionalFinite("latest", latest);
        ResultChecks.optionalFinite("min", min);
        ResultChecks.optionalFinite("max", max);
        ResultChecks.optionalFinite("average", average);
        Objects.requireNonNull(trend, "trend");
        if (sampleCount == 0) {
            if (latest != null || min != null || max != null || average != null) {
                throw new IllegalArgumentException("statistics must be absent without samples");
            }
        } else {
            if (latest == null || min == null || max == null || average == null) {
                throw new IllegalArgumentException("statistics must be present with samples");
            }
            if (min > max || average < min || average > max || latest < min || latest > max) {
                throw new IllegalArgumentException("statistics must satisfy min <= average,latest <= max");
            }
            if (sampleCount == 1 && (!min.equals(max) || !average.equals(min) || !latest.equals(min))) {
                throw new IllegalArgumentException("a single sample must have equal statistics");
            }
        }
        if (sampleCount < 2 && trend != Trend.UNKNOWN) {
            throw new IllegalArgumentException("trend must be UNKNOWN with fewer than 2 samples");
        }
        if (previousWindow != null
                && (!previousWindow.window().end().equals(window.start())
                        || !previousWindow.window().length().equals(window.length()))) {
            throw new IllegalArgumentException("previousWindow must be adjacent and of equal length");
        }
        boolean comparable = average != null
                && previousWindow != null
                && previousWindow.average() != null
                && previousWindow.average() != 0;
        if (changePercent != null && !comparable) {
            throw new IllegalArgumentException("changePercent requires sampled current and non-zero previous average");
        }
        Double derived = comparable ? (average - previousWindow.average()) / previousWindow.average() * 100 : null;
        if (changePercent != null && !(Math.abs(changePercent - derived) <= CHANGE_PERCENT_TOLERANCE)) {
            throw new IllegalArgumentException("changePercent must match average and previousWindow.average");
        }
        changePercent = derived;
    }

    @Override
    public CapabilitySchema resultSchema() {
        return new CapabilitySchema(SCHEMA_NAME, SCHEMA_VERSION);
    }
}
