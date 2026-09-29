package io.github.ismoyuan.opspilot.application.capability.metrics;

import io.github.ismoyuan.opspilot.application.capability.QueryWindow;
import io.github.ismoyuan.opspilot.application.capability.ResolvedWindow;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1.PreviousWindow;
import io.github.ismoyuan.opspilot.application.capability.result.TimeRange;
import io.github.ismoyuan.opspilot.application.capability.result.Trend;
import java.util.Comparator;
import java.util.List;

/**
 * 把真实时序点确定性地汇总为 metrics.query.result / 1（06 §42～§43）：只用落在半开窗口 [start, end) 内的有限值；NaN 与无穷（如无流量时的
 * 分位数、0/0 的比率）剔除且不当 0，没有有效样本时统计为空；前一窗口同样处理，没有样本时平均值为空（不做比较）。changePercent 由结果类型
 * 按两侧平均值派生。
 *
 * <p>趋势只描述当前窗口内部：按时间排序后前半与后半有效样本的平均值比较，相对变化超过阈值为 INCREASING/DECREASING，否则 STABLE；
 * 有效样本少于 4 个（每半至少 2 个）为 UNKNOWN。与前一窗口的比较由 changePercent 表达，两者不混用。
 *
 * <p>无状态、线程安全，由 Provider 装配时创建。
 */
public class MetricSeriesSummarizer {

    static final int MIN_SAMPLES_FOR_TREND = 4;

    private final MetricsSettings settings;

    public MetricSeriesSummarizer(MetricsSettings settings) {
        this.settings = settings;
    }

    /**
     * @param previousPoints 未要求比较（window.previous() 为空）时忽略
     */
    public MetricsQueryResultV1 summarize(
            String metricKey,
            String unit,
            ResolvedWindow window,
            List<MetricPoint> currentPoints,
            List<MetricPoint> previousPoints) {
        TimeRange current = range(window.current());
        List<MetricPoint> samples = validSamples(currentPoints, current);
        PreviousWindow previous = null;
        if (window.previous() != null) {
            TimeRange previousRange = range(window.previous());
            List<MetricPoint> previousSamples = validSamples(previousPoints, previousRange);
            previous = new PreviousWindow(
                    previousRange, previousSamples.size(), previousSamples.isEmpty() ? null : average(previousSamples));
        }
        if (samples.isEmpty()) {
            return new MetricsQueryResultV1(
                    metricKey, unit, current, 0, null, null, null, null, previous, null, Trend.UNKNOWN);
        }
        double min = samples.stream().mapToDouble(MetricPoint::value).min().orElseThrow();
        double max = samples.stream().mapToDouble(MetricPoint::value).max().orElseThrow();
        // 平均值夹在 [min, max]：浮点累加误差不能让它越界
        double average = Math.min(max, Math.max(min, average(samples)));
        double latest = samples.getLast().value();
        return new MetricsQueryResultV1(
                metricKey, unit, current, samples.size(), latest, min, max, average, previous, null, trend(samples));
    }

    private Trend trend(List<MetricPoint> samples) {
        if (samples.size() < MIN_SAMPLES_FOR_TREND) {
            return Trend.UNKNOWN;
        }
        int half = samples.size() / 2;
        double first = average(samples.subList(0, half));
        double second = average(samples.subList(samples.size() - half, samples.size()));
        double scale = Math.max(Math.abs(first), Math.abs(second));
        if (scale == 0) {
            return Trend.STABLE;
        }
        double relative = (second - first) / scale;
        if (relative > settings.trendThreshold()) {
            return Trend.INCREASING;
        }
        if (relative < -settings.trendThreshold()) {
            return Trend.DECREASING;
        }
        return Trend.STABLE;
    }

    private static List<MetricPoint> validSamples(List<MetricPoint> points, TimeRange range) {
        return points.stream()
                .filter(point -> Double.isFinite(point.value()) && range.contains(point.timestamp()))
                .sorted(Comparator.comparing(MetricPoint::timestamp))
                .toList();
    }

    private static double average(List<MetricPoint> points) {
        return points.stream().mapToDouble(MetricPoint::value).average().orElseThrow();
    }

    private static TimeRange range(QueryWindow window) {
        return new TimeRange(window.start(), window.end());
    }
}
