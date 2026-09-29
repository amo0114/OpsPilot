package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.ismoyuan.opspilot.application.capability.QueryWindow;
import io.github.ismoyuan.opspilot.application.capability.ResolvedWindow;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricPoint;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricSeriesSummarizer;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricsSettings;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.Trend;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 08 TASK-052、06 §42～§43：只用半开窗口内的有限样本；NaN/Inf、空序列、缺前一窗口不当 0；窗口内趋势的确定性规则。 */
class MetricSeriesSummarizerTest {

    static final Instant END = Instant.parse("2026-09-29T08:00:00Z");
    static final QueryWindow CURRENT = new QueryWindow(END.minusSeconds(600), END);
    static final QueryWindow PREVIOUS = new QueryWindow(END.minusSeconds(1200), END.minusSeconds(600));

    private final MetricSeriesSummarizer summarizer = new MetricSeriesSummarizer(new MetricsSettings(0.1));

    @Test
    void statisticsUseOnlyFiniteSamplesInsideTheHalfOpenWindow() {
        List<MetricPoint> points = List.of(
                point(-601, 999), // 窗口前
                point(-600, 10), // start 属于窗口
                point(-500, Double.NaN),
                point(-400, 30),
                point(-300, Double.POSITIVE_INFINITY),
                point(-200, 20),
                point(0, 999)); // end 不属于窗口

        MetricsQueryResultV1 result = summarize(points, List.of(), false);

        assertThat(result.sampleCount()).isEqualTo(3);
        assertThat(result.min()).isEqualTo(10.0);
        assertThat(result.max()).isEqualTo(30.0);
        assertThat(result.average()).isEqualTo(20.0);
        assertThat(result.latest()).isEqualTo(20.0);
        assertThat(result.trend()).isEqualTo(Trend.UNKNOWN); // 少于 4 个样本
        assertThat(result.previousWindow()).isNull();
        assertThat(result.changePercent()).isNull();
    }

    @Test
    void anEmptyOrAllNanSeriesHasNoStatistics() {
        for (List<MetricPoint> points : List.of(List.<MetricPoint>of(), List.of(point(-100, Double.NaN)))) {
            MetricsQueryResultV1 result = summarize(points, List.of(), false);
            assertThat(result.sampleCount()).isZero();
            assertThat(result.average()).isNull();
            assertThat(result.latest()).isNull();
            assertThat(result.trend()).isEqualTo(Trend.UNKNOWN);
        }
    }

    @Test
    void aMissingPreviousWindowIsNotComparedAsZero() {
        MetricsQueryResultV1 result = summarize(series(-600, 50, 50, 50, 50), List.of(point(-900, Double.NaN)), true);

        assertThat(result.previousWindow().window().start()).isEqualTo(PREVIOUS.start());
        assertThat(result.previousWindow().sampleCount()).isZero();
        assertThat(result.previousWindow().average()).isNull();
        assertThat(result.changePercent()).isNull();
    }

    @Test
    void theComparisonUsesTheRealPreviousAverage() {
        MetricsQueryResultV1 result =
                summarize(series(-600, 1600, 1600, 1600, 1600), series(-1200, 80, 90, 80, 70), true);

        assertThat(result.previousWindow().average()).isEqualTo(80.0);
        assertThat(result.changePercent()).isCloseTo(1900.0, within(1e-9));
        assertThat(result.trend()).isEqualTo(Trend.STABLE);
    }

    @Test
    void theTrendComparesTheSecondHalfWithTheFirstHalfOfTheWindow() {
        assertThat(summarize(series(-600, 10, 10, 20, 20), List.of(), false).trend())
                .isEqualTo(Trend.INCREASING);
        assertThat(summarize(series(-600, 20, 20, 10, 10), List.of(), false).trend())
                .isEqualTo(Trend.DECREASING);
        assertThat(summarize(series(-600, 100, 100, 105, 105), List.of(), false).trend())
                .isEqualTo(Trend.STABLE);
        assertThat(summarize(series(-600, 0, 0, 0, 0), List.of(), false).trend())
                .isEqualTo(Trend.STABLE);
        // 奇数个：中间点不参与
        assertThat(summarize(series(-600, 10, 10, 999, 20, 20), List.of(), false)
                        .trend())
                .isEqualTo(Trend.INCREASING);
    }

    @Test
    void theLatestValueIsTheLastSampleInTime() {
        List<MetricPoint> unordered = List.of(point(-100, 3), point(-500, 1), point(-300, 2));
        assertThat(summarize(unordered, List.of(), false).latest()).isEqualTo(3.0);
    }

    private MetricsQueryResultV1 summarize(List<MetricPoint> current, List<MetricPoint> previous, boolean compare) {
        return summarizer.summarize(
                "http.request.latency.p99",
                "ms",
                new ResolvedWindow(CURRENT, compare ? PREVIOUS : null),
                current,
                previous);
    }

    private static List<MetricPoint> series(int startOffsetSeconds, double... values) {
        List<MetricPoint> points = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            points.add(point(startOffsetSeconds + i * 60, values[i]));
        }
        return points;
    }

    private static MetricPoint point(int offsetSeconds, double value) {
        return new MetricPoint(END.plusSeconds(offsetSeconds), value);
    }
}
