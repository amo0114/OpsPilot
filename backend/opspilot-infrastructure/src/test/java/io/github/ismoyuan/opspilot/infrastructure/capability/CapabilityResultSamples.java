package io.github.ismoyuan.opspilot.infrastructure.capability;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InspectionType;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.capability.result.CacheInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.ConnectionSummary;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LockWait;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LockWaits;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LongestConnection;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.ServerSummary;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.SlowQuery;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.StateCount;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1.LogPattern;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1.PreviousWindow;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1.ConsumerGroup;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1.QueueType;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.HealthStatus;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.application.capability.result.TimeRange;
import io.github.ismoyuan.opspilot.application.capability.result.Trend;
import java.time.Instant;
import java.util.List;

/** 各 Capability 的说明性结果（数值取自 06 示例，不是实测）；部分文本故意带凭据，用于验证脱敏。 */
public final class CapabilityResultSamples {

    public static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    public static final TimeRange WINDOW = new TimeRange(NOW.minusSeconds(900), NOW);
    public static final TimeRange PREVIOUS = new TimeRange(NOW.minusSeconds(1800), NOW.minusSeconds(900));

    private CapabilityResultSamples() {}

    public static MetricsQueryResultV1 metrics() {
        return new MetricsQueryResultV1(
                "http.request.latency.p99",
                "ms",
                WINDOW,
                34,
                1640.0,
                78.0,
                1812.0,
                1301.0,
                new PreviousWindow(PREVIOUS, 34, 84.0),
                1448.81,
                Trend.INCREASING);
    }

    public static LogsSearchResultV1 logs() {
        return new LogsSearchResultV1(
                WINDOW,
                188,
                false,
                List.of(
                        new LogPattern(
                                "Redis command timed out after <NUM> ms",
                                LogSeverity.ERROR,
                                147,
                                NOW.minusSeconds(600),
                                NOW.minusSeconds(30),
                                List.of(
                                        "Redis command timed out after 2000 ms password=hunter2",
                                        "GET /s/Ab3x Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.e30.sig")),
                        new LogPattern(
                                "Connection refused to <IP>",
                                LogSeverity.WARN,
                                32,
                                NOW.minusSeconds(500),
                                NOW.minusSeconds(100),
                                List.of())));
    }

    public static CacheInspectResultV1 cache() {
        return new CacheInspectResultV1(
                true,
                623L,
                913_428_480L,
                2_147_483_648L,
                43L,
                8L,
                1850L,
                18_374_231L,
                2_811_021L,
                0.8673,
                12_431L,
                92_811L,
                73_124L);
    }

    public static DatabaseInspectResultV1 slowQueries() {
        return new DatabaseInspectResultV1(
                InspectionType.SLOW_QUERIES,
                null,
                null,
                List.of(
                        new SlowQuery(
                                "abc123",
                                "SELECT * FROM t_link WHERE short_uri = ? AND token = 'leak'",
                                4132,
                                6210,
                                8301,
                                186_231.0,
                                NOW.minusSeconds(5)),
                        new SlowQuery(
                                "def456",
                                "UPDATE t_link_stats SET pv = pv + ? WHERE id = ?",
                                20,
                                12.5,
                                40,
                                null,
                                null)),
                null);
    }

    public static DatabaseInspectResultV1 serverSummary() {
        return new DatabaseInspectResultV1(
                InspectionType.SERVER_SUMMARY,
                new ServerSummary(151, 37, 9_000_000, 812, 73_124, 0.93),
                null,
                null,
                null);
    }

    public static DatabaseInspectResultV1 connections() {
        return new DatabaseInspectResultV1(
                InspectionType.CONNECTION_SUMMARY,
                null,
                new ConnectionSummary(
                        151,
                        37,
                        List.of(
                                new StateCount("Sleep", 110),
                                new StateCount("executing", 37),
                                new StateCount("Waiting for table metadata lock", 4)),
                        new LongestConnection(38, "Query", "executing")),
                null,
                null);
    }

    public static DatabaseInspectResultV1 lockWaits() {
        return new DatabaseInspectResultV1(
                InspectionType.LOCK_WAITS,
                null,
                null,
                null,
                new LockWaits(
                        2, 17L, List.of(new LockWait(81, 64, 17, "shortlink.t_link"), new LockWait(82, 64, 3, null))));
    }

    public static QueueInspectResultV1 queue(Long lag) {
        return new QueueInspectResultV1(
                QueueType.REDIS_STREAM,
                2400,
                "1789992000000-0",
                Instant.parse("2026-09-21T12:00:00Z"),
                List.of(new ConsumerGroup(
                        "stats-consumer-group", 1, 4, lag, "1789991999000-0", Instant.parse("2026-09-21T11:59:59Z"))));
    }

    public static ServiceInspectResultV1 service() {
        return new ServiceInspectResultV1(
                RuntimeState.STOPPED,
                HealthStatus.UNHEALTHY,
                NOW.minusSeconds(7200),
                1,
                "registry.example.com/shortlink-consumer:demo",
                1,
                NOW.minusSeconds(3420));
    }
}
