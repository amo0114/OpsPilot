package io.github.ismoyuan.opspilot.infrastructure.capability;

import static io.github.ismoyuan.opspilot.infrastructure.capability.CapabilityResultSamples.NOW;
import static io.github.ismoyuan.opspilot.infrastructure.capability.CapabilityResultSamples.PREVIOUS;
import static io.github.ismoyuan.opspilot.infrastructure.capability.CapabilityResultSamples.WINDOW;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.capability.ObservationDraft;
import io.github.ismoyuan.opspilot.application.capability.extract.DatabaseStatusObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.LogPatternObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.MetricObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.ObservationExtractor;
import io.github.ismoyuan.opspilot.application.capability.result.CapabilityResult;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1.LogPattern;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1.PreviousWindow;
import io.github.ismoyuan.opspilot.application.capability.result.Trend;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.domain.observation.NewObservation;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import io.github.ismoyuan.opspilot.infrastructure.schema.SchemaCodecs;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 08 TASK-051、06 §29～§30、§44、§55、§65、§79、§89、§98：确定性提取——同一结果得到同一组 Observation；摘要只写真实值，
 * 不从单点写趋势、没有前窗口样本不写比较、单次队列采样不写增长；一次调用可产生多条。
 */
class ObservationExtractorTest {

    private final SchemaCodecRegistry codecs = SchemaCodecs.registry();
    private final Sanitizer sanitizer = new Sanitizer(new SanitizerSettings(true));
    private final ObservationExtractor extractor = new ObservationExtractor(codecs);

    @Test
    void metricsYieldOneObservationWithTheQueriedWindow() {
        List<ObservationDraft> drafts = extract(CapabilityResultSamples.metrics());

        assertThat(drafts).hasSize(1);
        ObservationDraft draft = drafts.getFirst();
        assertThat(draft.kind()).isEqualTo(ObservationKind.METRIC);
        assertThat(draft.schemaName()).isEqualTo("metric.observation");
        assertThat(draft.schemaVersion()).isOne();
        assertThat(draft.observedAt()).isEqualTo(NOW);
        assertThat(draft.windowStart()).isEqualTo(WINDOW.start());
        assertThat(draft.windowEnd()).isEqualTo(WINDOW.end());
        assertThat(draft.summary())
                .isEqualTo("http.request.latency.p99 在 2026-09-29T07:45:00Z～2026-09-29T08:00:00Z 内 34 个样本："
                        + "平均 1301 ms，最新 1640 ms，最小 78 ms，最大 1812 ms，趋势 INCREASING；"
                        + "前一窗口 2026-09-29T07:30:00Z～2026-09-29T07:45:00Z 平均 84 ms，变化 +1448.81%");
        assertThat(codecs.decode(draft.schemaName(), 1, draft.payload(), MetricObservationV1.class)
                        .previousWindow()
                        .average())
                .isEqualTo(84.0);
    }

    @Test
    void metricSummariesDoNotInventTrendsOrComparisons() {
        String single = summary(metric(1, 5.0, null, null, Trend.UNKNOWN));
        assertThat(single).contains("1 个样本").doesNotContain("趋势").doesNotContain("变化");

        String missingPrevious = summary(metric(3, 5.0, new PreviousWindow(PREVIOUS, 0, null), null, Trend.STABLE));
        assertThat(missingPrevious).contains("前一窗口").endsWith("没有有效样本，无法比较").doesNotContain("变化");

        String zeroBaseline = summary(metric(3, 5.0, new PreviousWindow(PREVIOUS, 3, 0.0), null, Trend.STABLE));
        assertThat(zeroBaseline).endsWith("平均 0 ms").doesNotContain("变化");

        String empty = summary(metric(0, null, null, null, Trend.UNKNOWN));
        assertThat(empty).endsWith("内没有有效样本").doesNotContain("平均");

        String decreasing = summary(metric(3, 5.0, new PreviousWindow(PREVIOUS, 3, 10.0), -50.0, Trend.DECREASING));
        assertThat(decreasing).endsWith("变化 -50%");
    }

    /** 每个日志模式一条 Observation，Sample 已脱敏；截断时计数写为下限。 */
    @Test
    void eachLogPatternBecomesOneSanitizedObservation() {
        List<ObservationDraft> drafts = extract(CapabilityResultSamples.logs());

        assertThat(drafts).extracting(ObservationDraft::kind).containsOnly(ObservationKind.LOG_PATTERN);
        assertThat(drafts).hasSize(2);
        assertThat(drafts.getFirst().summary())
                .isEqualTo("ERROR 日志模式「Redis command timed out after <NUM> ms」在 2026-09-29T07:45:00Z～"
                        + "2026-09-29T08:00:00Z 内出现 147 次，首次 2026-09-29T07:50:00Z，最近 2026-09-29T07:59:30Z；"
                        + "样例：Redis command timed out after 2000 ms password=[REDACTED] | "
                        + "GET /s/Ab3x Authorization: [REDACTED]");
        assertThat(drafts.get(1).summary())
                .startsWith("WARN 日志模式「Connection refused to <IP>」")
                .doesNotContain("样例");
        for (ObservationDraft draft : drafts) {
            assertThat(draft.payload()).doesNotContain("hunter2").doesNotContain("eyJ");
            assertThat(draft.windowStart()).isEqualTo(WINDOW.start());
        }
        LogPatternObservationV1 payload =
                codecs.decode("log-pattern.observation", 1, drafts.getFirst().payload(), LogPatternObservationV1.class);
        assertThat(payload.count()).isEqualTo(147);
        assertThat(payload.totalMatches()).isEqualTo(188);

        LogsSearchResultV1 truncated = new LogsSearchResultV1(
                WINDOW,
                500,
                true,
                List.of(new LogPattern("x <NUM>", null, 500, WINDOW.start(), NOW.minusSeconds(1), List.of())));
        assertThat(summary(truncated)).startsWith("未知级别 日志模式「x <NUM>」").contains("500 次（已达到查询上限，计数为下限）");
    }

    @Test
    void aLogSearchWithoutPatternsYieldsOneTruthfulObservation() {
        List<ObservationDraft> drafts = extract(new LogsSearchResultV1(WINDOW, 0, false, List.of()));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.getFirst().summary()).isEqualTo("日志查询在 2026-09-29T07:45:00Z～2026-09-29T08:00:00Z 内没有匹配行");
        assertThat(codecs.decode(
                                "log-pattern.observation",
                                1,
                                drafts.getFirst().payload(),
                                LogPatternObservationV1.class)
                        .count())
                .isZero();
    }

    @Test
    void cacheStatusIsSummarizedFromMeasuredValuesOnly() {
        List<ObservationDraft> drafts = extract(CapabilityResultSamples.cache());

        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.kind()).isEqualTo(ObservationKind.CACHE_STATUS);
            assertThat(draft.windowStart()).isNull();
            assertThat(draft.summary())
                    .isEqualTo("缓存可达，PING 延迟 623 ms，已用内存 871.11 MiB / 上限 2048 MiB，已连接客户端 43，阻塞客户端 8，"
                            + "每秒操作 1850，命中率 86.73%，累计驱逐 Key 12431，累计过期 Key 92811，已运行 73124 秒");
        });
    }

    /** SLOW_QUERIES：一条汇总加每条语句一条，只有当前统计，不写基线比较；SQL 中的凭据已清理。 */
    @Test
    void slowQueriesYieldAnOverviewAndOneObservationPerStatement() {
        List<ObservationDraft> drafts = extract(CapabilityResultSamples.slowQueries());

        assertThat(drafts).hasSize(3).extracting(ObservationDraft::kind).containsOnly(ObservationKind.DATABASE_STATUS);
        assertThat(drafts)
                .extracting(ObservationDraft::summary)
                .containsExactly(
                        "语句统计返回 2 条语句，最高平均耗时 6210 ms，合计执行 4152 次",
                        "语句 abc123 执行 4132 次，平均 6210 ms，最大 8301 ms，平均扫描 186231 行，"
                                + "最近 2026-09-29T07:59:55Z：SELECT * FROM t_link WHERE short_uri = ? AND token = '[REDACTED]'",
                        "语句 def456 执行 20 次，平均 12.5 ms，最大 40 ms：UPDATE t_link_stats SET pv = pv + ? WHERE id = ?");
        assertThat(drafts)
                .allSatisfy(draft -> assertThat(draft.summary() + draft.payload())
                        .doesNotContain("leak")
                        .doesNotContain("正常")
                        .doesNotContain("升高"));
        assertThat(codecs.decode(
                                "database-status.observation",
                                1,
                                drafts.get(1).payload(),
                                DatabaseStatusObservationV1.class)
                        .slowQuery()
                        .digest())
                .isEqualTo("abc123");
    }

    @Test
    void otherDatabaseInspectionsYieldOneObservation() {
        assertThat(summary(CapabilityResultSamples.serverSummary()))
                .isEqualTo("MySQL 服务端：已连接线程 151，运行线程 37，启动以来 Questions 9000000、慢查询 812，已运行 73124 秒，"
                        + "Buffer Pool 使用率 93%");
        assertThat(summary(CapabilityResultSamples.connections()))
                .isEqualTo("MySQL 当前连接 151，运行中 37；状态分布：Sleep 110、executing 37、"
                        + "Waiting for table metadata lock 4；最长运行连接 38 秒（Query / executing）");
        assertThat(summary(CapabilityResultSamples.lockWaits()))
                .isEqualTo("当前锁等待 2 个，最长等待 17 秒；阻塞关系：线程 81 等待线程 64 17 秒（shortlink.t_link）；" + "线程 82 等待线程 64 3 秒");
    }

    /** 单次队列采样只描述当前值；lag 未知写“未知”，不当 0，也不写增长。 */
    @Test
    void queueStatusDescribesOneSampleWithoutTrend() {
        String unknownLag = summary(CapabilityResultSamples.queue(null));
        assertThat(unknownLag)
                .isEqualTo("本次采样 Stream 长度 2400，最近生成消息 1789992000000-0（2026-09-21T12:00:00Z）；"
                        + "消费组 stats-consumer-group：尚未投递积压 未知，已投递未确认 4，登记消费者 1，最近投递 2026-09-21T11:59:59Z");
        assertThat(summary(CapabilityResultSamples.queue(2180L)))
                .contains("尚未投递积压 2180")
                .doesNotContain("增长")
                .doesNotContain("持续");
    }

    @Test
    void serviceStatusUsesOnlyWhitelistedFields() {
        assertThat(extract(CapabilityResultSamples.service())).singleElement().satisfies(draft -> {
            assertThat(draft.kind()).isEqualTo(ObservationKind.SERVICE_STATUS);
            assertThat(draft.summary())
                    .isEqualTo("服务运行状态 STOPPED，健康状态 UNHEALTHY，启动于 2026-09-29T06:00:00Z，重启 1 次，退出码 1，"
                            + "结束于 2026-09-29T07:03:00Z");
        });
    }

    @Test
    void extractionIsDeterministicAndSummariesFitTheColumn() {
        for (CapabilityResult result : List.of(
                CapabilityResultSamples.metrics(),
                CapabilityResultSamples.logs(),
                CapabilityResultSamples.cache(),
                CapabilityResultSamples.slowQueries(),
                CapabilityResultSamples.queue(1L),
                CapabilityResultSamples.service())) {
            assertThat(extract(result)).isEqualTo(extract(result));
        }
        String longText = "长".repeat(2000);
        LogsSearchResultV1 huge = new LogsSearchResultV1(
                WINDOW,
                2,
                false,
                List.of(new LogPattern(
                        longText,
                        LogSeverity.ERROR,
                        2,
                        WINDOW.start(),
                        NOW.minusSeconds(1),
                        List.of(longText, longText))));
        String summary = summary(huge);
        assertThat(summary.codePointCount(0, summary.length())).isLessThanOrEqualTo(NewObservation.SUMMARY_MAX);
        assertThat(summary).contains("…");
    }

    private List<ObservationDraft> extract(CapabilityResult result) {
        return extractor.extract(sanitizer.sanitizeResult(result), NOW);
    }

    private String summary(CapabilityResult result) {
        return extract(result).getFirst().summary();
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
