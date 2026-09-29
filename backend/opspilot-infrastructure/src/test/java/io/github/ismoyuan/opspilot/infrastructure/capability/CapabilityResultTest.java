package io.github.ismoyuan.opspilot.infrastructure.capability;

import static io.github.ismoyuan.opspilot.infrastructure.capability.CapabilityResultSamples.NOW;
import static io.github.ismoyuan.opspilot.infrastructure.capability.CapabilityResultSamples.PREVIOUS;
import static io.github.ismoyuan.opspilot.infrastructure.capability.CapabilityResultSamples.WINDOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InspectionType;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.capability.result.CacheInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.ConnectionSummary;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LockWait;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LockWaits;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.ServerSummary;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.StateCount;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1.LogPattern;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1.PreviousWindow;
import io.github.ismoyuan.opspilot.application.capability.result.TimeRange;
import io.github.ismoyuan.opspilot.application.capability.result.Trend;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 08 TASK-051、06 §42～§43、§86：结果类型在构造时拒绝未测事实——空样本当 0、单点趋势、无前窗口的比较、NaN、计数不自洽。 */
class CapabilityResultTest {

    @Test
    void resultSchemasMatchTheRegistry() {
        CapabilityRegistry registry = CapabilityRegistry.v01(CapabilityRegistry.DEFAULT_TIMEOUTS);
        assertThat(CapabilityResultSamples.metrics().resultSchema())
                .isEqualTo(registry.definition(CapabilityKey.METRICS_QUERY).resultSchema());
        assertThat(CapabilityResultSamples.logs().resultSchema())
                .isEqualTo(registry.definition(CapabilityKey.LOGS_SEARCH).resultSchema());
        assertThat(CapabilityResultSamples.cache().resultSchema())
                .isEqualTo(registry.definition(CapabilityKey.CACHE_INSPECT).resultSchema());
        assertThat(CapabilityResultSamples.slowQueries().resultSchema())
                .isEqualTo(registry.definition(CapabilityKey.DATABASE_INSPECT).resultSchema());
        assertThat(CapabilityResultSamples.queue(1L).resultSchema())
                .isEqualTo(registry.definition(CapabilityKey.QUEUE_INSPECT).resultSchema());
        assertThat(CapabilityResultSamples.service().resultSchema())
                .isEqualTo(registry.definition(CapabilityKey.SERVICE_INSPECT).resultSchema());
    }

    @Test
    void metricsWithoutSamplesCarryNoStatistics() {
        assertThat(metric(0, null, null, null, Trend.UNKNOWN).sampleCount()).isZero();
        // 空时序不能写成 0
        assertThatThrownBy(() -> metric(0, 0.0, null, null, Trend.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metric(3, Double.NaN, null, null, Trend.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSingleSampleCannotHaveATrend() {
        assertThat(metric(1, 5.0, null, null, Trend.UNKNOWN).trend()).isEqualTo(Trend.UNKNOWN);
        assertThatThrownBy(() -> metric(1, 5.0, null, null, Trend.INCREASING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metric(0, null, null, null, Trend.STABLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changePercentNeedsARealNonZeroPreviousAverage() {
        assertThat(metric(3, 5.0, new PreviousWindow(PREVIOUS, 3, 2.5), 100.0, Trend.STABLE)
                        .changePercent())
                .isEqualTo(100.0);
        // 未比较、前窗口无样本、前窗口平均为 0、当前无样本：均不得给出变化
        assertThatThrownBy(() -> metric(3, 5.0, null, 100.0, Trend.STABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metric(3, 5.0, new PreviousWindow(PREVIOUS, 0, null), 100.0, Trend.STABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metric(3, 5.0, new PreviousWindow(PREVIOUS, 3, 0.0), 100.0, Trend.STABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metric(0, null, new PreviousWindow(PREVIOUS, 3, 2.5), -100.0, Trend.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
        // 前窗口缺样本不能当 0
        assertThatThrownBy(() -> new PreviousWindow(PREVIOUS, 0, 0.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePreviousWindowIsAdjacentAndOfEqualLength() {
        TimeRange gap =
                new TimeRange(PREVIOUS.start().minusSeconds(60), PREVIOUS.end().minusSeconds(60));
        TimeRange shorter = new TimeRange(PREVIOUS.start().plusSeconds(60), PREVIOUS.end());
        assertThatThrownBy(() -> metric(3, 5.0, new PreviousWindow(gap, 3, 2.5), null, Trend.STABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metric(3, 5.0, new PreviousWindow(shorter, 3, 2.5), null, Trend.STABLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logPatternCountsCannotExceedTheMatchesActuallyRead() {
        Instant last = NOW.minusSeconds(1);
        LogPattern pattern = new LogPattern("x <NUM>", LogSeverity.ERROR, 10, last, last, List.of());
        assertThat(new LogsSearchResultV1(WINDOW, 10, true, List.of(pattern)).patterns())
                .hasSize(1);
        assertThatThrownBy(() -> new LogsSearchResultV1(WINDOW, 9, false, List.of(pattern)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LogPattern("x", null, 0, NOW, NOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LogPattern("x", null, 1, NOW, NOW.minusSeconds(1), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cacheStatisticsAreOnlyPresentWhenMeasured() {
        assertThatThrownBy(() -> new CacheInspectResultV1(
                        false, 1L, null, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CacheInspectResultV1(
                        true, null, null, null, null, null, null, 0L, 0L, 0.0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CacheInspectResultV1(
                        true, null, null, null, null, null, null, 1L, 1L, 1.5, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseResultsCarryExactlyTheInspectedSection() {
        assertThatThrownBy(() -> new DatabaseInspectResultV1(
                        InspectionType.LOCK_WAITS,
                        CapabilityResultSamples.serverSummary().serverSummary(),
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseInspectResultV1(InspectionType.SLOW_QUERIES, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockWaits(0, 3L, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new LockWaits(1, 3L, List.of(new LockWait(1, 2, 3, null), new LockWait(4, 2, 1, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** B15-R1 P2：命中率由计数派生；与计数矛盾的值被拒绝，省略时补出，按三位小数给出的值接受。 */
    @Test
    void hitRateIsDerivedFromTheCounters() {
        assertThatThrownBy(() -> cache(1L, 9L, 0.9)).isInstanceOf(IllegalArgumentException.class);
        assertThat(cache(1L, 9L, null).hitRate()).isEqualTo(0.1);
        assertThat(cache(18_374_231L, 2_811_021L, 0.867).hitRate())
                .isEqualTo(18_374_231 / (double) (18_374_231 + 2_811_021));
        assertThat(cache(null, 9L, null).hitRate()).isNull();
        assertThatThrownBy(() -> new CacheInspectResultV1(
                        true, null, null, null, 3L, 4L, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** B15-R1 P2：整体最长等待不得小于任何列出的等待。 */
    @Test
    void theLongestLockWaitCoversEveryListedWait() {
        assertThatThrownBy(() -> new LockWaits(1, 3L, List.of(new LockWait(81, 64, 17, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LockWaits(2, 17L, List.of(new LockWait(81, 64, 17, null), new LockWait(82, 64, 3, null)))
                        .longestWaitSeconds())
                .isEqualTo(17L);
    }

    /** 同类一致性：同一结果内的汇总值与明细、计数与比值不能互相矛盾。 */
    @Test
    void derivedAndAggregateValuesCannotContradictTheirParts() {
        // changePercent 由两侧平均值派生，矛盾值拒绝，一位小数的取整接受
        assertThatThrownBy(() -> metric(3, 5.0, new PreviousWindow(PREVIOUS, 3, 2.5), 900.0, Trend.STABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(metric(3, 5.0, new PreviousWindow(PREVIOUS, 3, 2.5), null, Trend.STABLE)
                        .changePercent())
                .isEqualTo(100.0);
        assertThat(CapabilityResultSamples.metrics().changePercent()).isCloseTo(1448.8095, within(0.0001));
        // 单个样本的各统计值相等
        assertThatThrownBy(() ->
                        new MetricsQueryResultV1("m", "ms", WINDOW, 1, 5.0, 1.0, 9.0, 5.0, null, null, Trend.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
        // 日志：只出现一次时首末相同、样例不多于次数、时间在窗口内
        assertThatThrownBy(() -> new LogPattern("x", null, 1, NOW.minusSeconds(5), NOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LogPattern("x", null, 1, NOW, NOW, List.of("a", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        // 窗口为半开区间 [start, end)：恰在 end 或 start 之前的时间不属于本次查询（B15-R2）
        for (Instant outside : List.of(NOW, NOW.plusSeconds(1), WINDOW.start().minusMillis(1))) {
            assertThatThrownBy(() -> new LogsSearchResultV1(
                            WINDOW, 1, false, List.of(new LogPattern("x", null, 1, outside, outside, List.of()))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new LogsSearchResultV1(
                                WINDOW,
                                2,
                                false,
                                List.of(new LogPattern("x", null, 2, WINDOW.start(), NOW.minusMillis(1), List.of())))
                        .patterns())
                .hasSize(1);
        // 数据库：运行线程不超过已连接线程，状态计数之和不超过当前连接数
        assertThatThrownBy(() -> new ServerSummary(10, 11, 0, 0, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionSummary(
                        5, 1, List.of(new StateCount("Sleep", 4), new StateCount("executing", 2)), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CacheInspectResultV1 cache(Long hits, Long misses, Double hitRate) {
        return new CacheInspectResultV1(
                true, null, null, null, null, null, null, hits, misses, hitRate, null, null, null);
    }

    private static MetricsQueryResultV1 metric(
            int samples, Double value, PreviousWindow previous, Double changePercent, Trend trend) {
        return new MetricsQueryResultV1(
                "http.request.latency.p99",
                "ms",
                WINDOW,
                samples,
                value,
                value,
                value,
                value,
                previous,
                changePercent,
                trend);
    }
}
