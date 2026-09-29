package io.github.ismoyuan.opspilot.application.capability.extract;

import io.github.ismoyuan.opspilot.application.capability.extract.DatabaseStatusObservationV1.SlowQueryOverview;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.ConnectionSummary;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LockWait;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LockWaits;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.ServerSummary;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.SlowQuery;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.StateCount;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1.PreviousWindow;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1.ConsumerGroup;
import io.github.ismoyuan.opspilot.application.capability.result.TimeRange;
import io.github.ismoyuan.opspilot.application.capability.result.Trend;
import io.github.ismoyuan.opspilot.domain.observation.NewObservation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

/**
 * Observation 摘要的确定性文本（06 §44、§55、§65、§79、§89、§98）：只写载荷中真实存在的值，空值省略或写“未知”，不补推断。
 * 数字按 Locale 无关的十进制（至多两位小数）、时间按 UTC ISO-8601 到秒；摘要不超过 Observation summary 的 1000 字符。
 */
final class ObservationSummaries {

    /** 摘要中单段外部文本（日志模式、样例、SQL）的上限，保证整体不超长且不挤掉数值。 */
    static final int PATTERN_MAX = 300;

    static final int SAMPLE_MAX = 160;
    static final int SQL_MAX = 400;
    static final int LISTED_MAX = 5;

    private ObservationSummaries() {}

    static String metric(MetricObservationV1 metric) {
        StringBuilder text = new StringBuilder(metric.metricKey())
                .append(" 在 ")
                .append(range(metric.window()))
                .append(" 内");
        if (metric.sampleCount() == 0) {
            text.append("没有有效样本");
        } else {
            String unit = " " + metric.unit();
            text.append(' ')
                    .append(metric.sampleCount())
                    .append(" 个样本：平均 ")
                    .append(number(metric.average()))
                    .append(unit)
                    .append("，最新 ")
                    .append(number(metric.latest()))
                    .append(unit)
                    .append("，最小 ")
                    .append(number(metric.min()))
                    .append(unit)
                    .append("，最大 ")
                    .append(number(metric.max()))
                    .append(unit);
            if (metric.trend() != Trend.UNKNOWN) {
                text.append("，趋势 ").append(metric.trend());
            }
        }
        PreviousWindow previous = metric.previousWindow();
        if (previous != null) {
            text.append("；前一窗口 ").append(range(previous.window()));
            if (previous.average() == null) {
                text.append(" 没有有效样本，无法比较");
            } else {
                text.append(" 平均 ")
                        .append(number(previous.average()))
                        .append(' ')
                        .append(metric.unit());
                if (metric.changePercent() != null) {
                    text.append("，变化 ")
                            .append(metric.changePercent() > 0 ? "+" : "")
                            .append(number(metric.changePercent()))
                            .append('%');
                }
            }
        }
        return limit(text.toString());
    }

    static String logPattern(LogPatternObservationV1 log) {
        if (log.pattern() == null) {
            return limit("日志查询在 " + range(log.window()) + " 内"
                    + (log.totalMatches() == 0 ? "没有匹配行" : "匹配 " + log.totalMatches() + " 行，未形成模式"));
        }
        StringBuilder text = new StringBuilder(
                        log.severity() == null ? "未知级别" : log.severity().name())
                .append(" 日志模式「")
                .append(clip(log.pattern(), PATTERN_MAX))
                .append("」在 ")
                .append(range(log.window()))
                .append(" 内出现 ")
                .append(log.count())
                .append(" 次");
        if (log.truncated()) {
            text.append("（已达到查询上限，计数为下限）");
        }
        text.append("，首次 ").append(time(log.firstSeen())).append("，最近 ").append(time(log.lastSeen()));
        if (!log.samples().isEmpty()) {
            StringJoiner samples = new StringJoiner(" | ", "；样例：", "");
            log.samples().stream().limit(2).forEach(sample -> samples.add(clip(sample, SAMPLE_MAX)));
            text.append(samples);
        }
        return limit(text.toString());
    }

    static String cache(CacheStatusObservationV1 cache) {
        if (!cache.reachable()) {
            return "缓存不可达";
        }
        List<String> parts = new ArrayList<>();
        parts.add("缓存可达");
        add(parts, "PING 延迟 ", cache.pingLatencyMs(), " ms");
        if (cache.usedMemoryBytes() != null) {
            parts.add("已用内存 " + mebibytes(cache.usedMemoryBytes())
                    + (cache.maxMemoryBytes() == null ? "" : " / 上限 " + mebibytes(cache.maxMemoryBytes())));
        }
        add(parts, "已连接客户端 ", cache.connectedClients(), "");
        add(parts, "阻塞客户端 ", cache.blockedClients(), "");
        add(parts, "每秒操作 ", cache.instantOpsPerSec(), "");
        if (cache.hitRate() != null) {
            parts.add("命中率 " + number(cache.hitRate() * 100) + "%");
        }
        add(parts, "累计驱逐 Key ", cache.evictedKeys(), "");
        add(parts, "累计过期 Key ", cache.expiredKeys(), "");
        add(parts, "已运行 ", cache.uptimeSeconds(), " 秒");
        return limit(String.join("，", parts));
    }

    static String database(DatabaseStatusObservationV1 database) {
        if (database.serverSummary() != null) {
            return server(database.serverSummary());
        }
        if (database.connectionSummary() != null) {
            return connections(database.connectionSummary());
        }
        if (database.slowQueryOverview() != null) {
            return slowQueryOverview(database.slowQueryOverview());
        }
        if (database.slowQuery() != null) {
            return slowQuery(database.slowQuery());
        }
        return lockWaits(database.lockWaits());
    }

    private static String server(ServerSummary server) {
        return limit("MySQL 服务端：已连接线程 " + server.threadsConnected() + "，运行线程 " + server.threadsRunning()
                + "，启动以来 Questions " + server.questions() + "、慢查询 " + server.slowQueries() + "，已运行 "
                + server.uptimeSeconds() + " 秒"
                + (server.bufferPoolUsage() == null
                        ? ""
                        : "，Buffer Pool 使用率 " + number(server.bufferPoolUsage() * 100) + "%"));
    }

    private static String connections(ConnectionSummary connections) {
        StringBuilder text = new StringBuilder("MySQL 当前连接 ")
                .append(connections.totalConnections())
                .append("，运行中 ")
                .append(connections.runningConnections());
        if (!connections.states().isEmpty()) {
            StringJoiner states = new StringJoiner("、", "；状态分布：", "");
            connections.states().stream()
                    .sorted(Comparator.comparingLong(StateCount::count)
                            .reversed()
                            .thenComparing(StateCount::state))
                    .limit(LISTED_MAX)
                    .forEach(state -> states.add(clip(state.state(), 64) + " " + state.count()));
            text.append(states);
        }
        if (connections.longest() != null) {
            text.append("；最长运行连接 ")
                    .append(connections.longest().timeSeconds())
                    .append(" 秒（")
                    .append(clip(connections.longest().command(), 64))
                    .append(
                            connections.longest().state() == null
                                    ? ""
                                    : " / " + clip(connections.longest().state(), 64))
                    .append('）');
        }
        return limit(text.toString());
    }

    private static String slowQueryOverview(SlowQueryOverview overview) {
        if (overview.queryCount() == 0) {
            return "语句统计未返回任何语句";
        }
        return limit("语句统计返回 " + overview.queryCount() + " 条语句，最高平均耗时 " + number(overview.maxAverageLatencyMs())
                + " ms，合计执行 " + overview.totalExecutionCount() + " 次");
    }

    private static String slowQuery(SlowQuery query) {
        return limit("语句 " + clip(query.digest(), 16) + " 执行 " + query.executionCount() + " 次，平均 "
                + number(query.averageLatencyMs()) + " ms，最大 " + number(query.maxLatencyMs()) + " ms"
                + (query.averageRowsExamined() == null ? "" : "，平均扫描 " + number(query.averageRowsExamined()) + " 行")
                + (query.lastSeen() == null ? "" : "，最近 " + time(query.lastSeen()))
                + "：" + clip(query.normalizedSql(), SQL_MAX));
    }

    private static String lockWaits(LockWaits locks) {
        if (locks.waitingCount() == 0) {
            return "当前没有锁等待";
        }
        StringBuilder text = new StringBuilder("当前锁等待 ")
                .append(locks.waitingCount())
                .append(" 个，最长等待 ")
                .append(locks.longestWaitSeconds())
                .append(" 秒");
        if (!locks.waits().isEmpty()) {
            StringJoiner relations = new StringJoiner("；", "；阻塞关系：", "");
            locks.waits().stream()
                    .sorted(Comparator.comparingLong(LockWait::waitSeconds).reversed())
                    .limit(LISTED_MAX)
                    .forEach(wait -> relations.add("线程 " + wait.waitingThreadId() + " 等待线程 "
                            + wait.blockingThreadId() + " " + wait.waitSeconds() + " 秒"
                            + (wait.lockedObject() == null ? "" : "（" + clip(wait.lockedObject(), 128) + "）")));
            text.append(relations);
        }
        return limit(text.toString());
    }

    static String queue(QueueStatusObservationV1 queue) {
        StringBuilder text = new StringBuilder("本次采样 Stream 长度 ").append(queue.streamLength());
        if (queue.lastGeneratedId() != null || queue.lastGeneratedAt() != null) {
            text.append("，最近生成消息");
            if (queue.lastGeneratedId() != null) {
                text.append(' ').append(clip(queue.lastGeneratedId(), 64));
            }
            if (queue.lastGeneratedAt() != null) {
                text.append("（").append(time(queue.lastGeneratedAt())).append('）');
            }
        }
        if (queue.consumerGroups().isEmpty()) {
            text.append("；未取得消费组统计");
        }
        for (ConsumerGroup group : queue.consumerGroups()) {
            text.append("；消费组 ")
                    .append(clip(group.group(), 128))
                    .append("：尚未投递积压 ")
                    .append(group.lag() == null ? "未知" : group.lag().toString())
                    .append("，已投递未确认 ")
                    .append(group.pendingCount())
                    .append("，登记消费者 ")
                    .append(group.consumerCount());
            if (group.lastDeliveredAt() != null) {
                text.append("，最近投递 ").append(time(group.lastDeliveredAt()));
            }
        }
        return limit(text.toString());
    }

    static String service(ServiceStatusObservationV1 service) {
        StringBuilder text = new StringBuilder("服务运行状态 ")
                .append(service.runtimeState())
                .append("，健康状态 ")
                .append(service.healthStatus());
        if (service.startedAt() != null) {
            text.append("，启动于 ").append(time(service.startedAt()));
        }
        text.append("，重启 ").append(service.restartCount()).append(" 次");
        if (service.exitCode() != null) {
            text.append("，退出码 ").append(service.exitCode());
        }
        if (service.finishedAt() != null) {
            text.append("，结束于 ").append(time(service.finishedAt()));
        }
        return limit(text.toString());
    }

    static String number(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String mebibytes(long bytes) {
        return number(bytes / 1_048_576.0) + " MiB";
    }

    private static void add(List<String> parts, String label, Long value, String suffix) {
        if (value != null) {
            parts.add(label + value + suffix);
        }
    }

    static String time(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    private static String range(TimeRange range) {
        return time(range.start()) + "～" + time(range.end());
    }

    /** 按码点截断并以 … 结尾。 */
    static String clip(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
    }

    private static String limit(String text) {
        return clip(text, NewObservation.SUMMARY_MAX);
    }
}
