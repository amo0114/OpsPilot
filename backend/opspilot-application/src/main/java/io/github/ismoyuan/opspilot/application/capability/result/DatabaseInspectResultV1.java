package io.github.ismoyuan.opspilot.application.capability.result;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InspectionType;
import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * database.inspect.result / 1（06 §74～§78）：按固定检查类型读取的 MySQL 服务端统计，恰有与 inspectionType 对应的一节。
 * 只含 performance_schema / information_schema / SHOW STATUS 的统计与规范化 SQL，不含业务表数据或原始字面量（CAP-INV-010、06 §77）。
 * 应用连接池（HikariCP）指标不属于这里，来自 metrics.query（06 §75）。
 */
public record DatabaseInspectResultV1(
        InspectionType inspectionType,
        ServerSummary serverSummary,
        ConnectionSummary connectionSummary,
        List<SlowQuery> slowQueries,
        LockWaits lockWaits)
        implements CapabilityResult {

    public static final String SCHEMA_NAME = "database.inspect.result";
    public static final int SCHEMA_VERSION = 1;

    /** SERVER_SUMMARY（06 §74）；questions、slowQueries 为服务端启动以来的累计值；运行线程不超过已连接线程。 */
    public record ServerSummary(
            long threadsConnected,
            long threadsRunning,
            long questions,
            long slowQueries,
            long uptimeSeconds,
            Double bufferPoolUsage) {

        public ServerSummary {
            ResultChecks.nonNegative("threadsConnected", threadsConnected);
            ResultChecks.nonNegative("threadsRunning", threadsRunning);
            ResultChecks.nonNegative("questions", questions);
            ResultChecks.nonNegative("slowQueries", slowQueries);
            ResultChecks.nonNegative("uptimeSeconds", uptimeSeconds);
            ResultChecks.optionalRatio("bufferPoolUsage", bufferPoolUsage);
            if (threadsRunning > threadsConnected) {
                throw new IllegalArgumentException("threadsRunning must not exceed threadsConnected");
            }
        }
    }

    /** CONNECTION_SUMMARY（06 §75）：当前连接数、运行连接数、状态分布（各状态计数之和不超过当前连接数）与最长运行连接。 */
    public record ConnectionSummary(
            long totalConnections, long runningConnections, List<StateCount> states, LongestConnection longest) {

        public ConnectionSummary {
            ResultChecks.nonNegative("totalConnections", totalConnections);
            ResultChecks.nonNegative("runningConnections", runningConnections);
            if (runningConnections > totalConnections) {
                throw new IllegalArgumentException("runningConnections must not exceed totalConnections");
            }
            states = ResultChecks.list("states", states);
            long listed = 0;
            for (StateCount state : states) {
                listed += state.count();
            }
            if (listed > totalConnections) {
                throw new IllegalArgumentException("state counts must not exceed totalConnections");
            }
        }
    }

    public record StateCount(String state, long count) {

        public StateCount {
            ResultChecks.text("state", state);
            ResultChecks.nonNegative("count", count);
        }
    }

    /** 运行时间最长的连接；不含其 SQL 文本（可能带业务字面量）。 */
    public record LongestConnection(long timeSeconds, String command, String state) {

        public LongestConnection {
            ResultChecks.nonNegative("timeSeconds", timeSeconds);
            ResultChecks.text("command", command);
            ResultChecks.optionalText("state", state);
        }
    }

    /** SLOW_QUERIES 的一条 digest 统计（06 §76）；normalizedSql 为规范化语句（字面量为 ?）。 */
    public record SlowQuery(
            String digest,
            String normalizedSql,
            long executionCount,
            double averageLatencyMs,
            double maxLatencyMs,
            Double averageRowsExamined,
            Instant lastSeen) {

        public SlowQuery {
            ResultChecks.text("digest", digest);
            ResultChecks.text("normalizedSql", normalizedSql);
            ResultChecks.nonNegative("executionCount", executionCount);
            ResultChecks.finite("averageLatencyMs", averageLatencyMs);
            ResultChecks.finite("maxLatencyMs", maxLatencyMs);
            ResultChecks.optionalFinite("averageRowsExamined", averageRowsExamined);
            if (averageLatencyMs > maxLatencyMs) {
                throw new IllegalArgumentException("averageLatencyMs must not exceed maxLatencyMs");
            }
        }
    }

    /**
     * LOCK_WAITS（06 §78）：等待数量、最长等待与阻塞关系摘要；没有等待时 longestWaitSeconds 为空，列出的每个等待都不超过它（B15-R1）。
     */
    public record LockWaits(long waitingCount, Long longestWaitSeconds, List<LockWait> waits) {

        public LockWaits {
            ResultChecks.nonNegative("waitingCount", waitingCount);
            ResultChecks.optionalNonNegative("longestWaitSeconds", longestWaitSeconds);
            waits = ResultChecks.list("waits", waits);
            if ((waitingCount == 0) != (longestWaitSeconds == null) || waits.size() > waitingCount) {
                throw new IllegalArgumentException("lock waits must be consistent with waitingCount");
            }
            for (LockWait wait : waits) {
                if (wait.waitSeconds() > longestWaitSeconds) {
                    throw new IllegalArgumentException("longestWaitSeconds must not be less than any listed wait");
                }
            }
        }
    }

    /** @param lockedObject 被锁对象（schema.table），未知时为空 */
    public record LockWait(long waitingThreadId, long blockingThreadId, long waitSeconds, String lockedObject) {

        public LockWait {
            ResultChecks.nonNegative("waitingThreadId", waitingThreadId);
            ResultChecks.nonNegative("blockingThreadId", blockingThreadId);
            ResultChecks.nonNegative("waitSeconds", waitSeconds);
            ResultChecks.optionalText("lockedObject", lockedObject);
        }
    }

    public DatabaseInspectResultV1 {
        Objects.requireNonNull(inspectionType, "inspectionType");
        slowQueries = slowQueries == null ? null : ResultChecks.list("slowQueries", slowQueries);
        boolean consistent = switch (inspectionType) {
            case SERVER_SUMMARY ->
                serverSummary != null && connectionSummary == null && slowQueries == null && lockWaits == null;
            case CONNECTION_SUMMARY ->
                serverSummary == null && connectionSummary != null && slowQueries == null && lockWaits == null;
            case SLOW_QUERIES ->
                serverSummary == null && connectionSummary == null && slowQueries != null && lockWaits == null;
            case LOCK_WAITS ->
                serverSummary == null && connectionSummary == null && slowQueries == null && lockWaits != null;
        };
        if (!consistent) {
            throw new IllegalArgumentException("exactly the section of inspectionType must be present");
        }
    }

    @Override
    public CapabilitySchema resultSchema() {
        return new CapabilitySchema(SCHEMA_NAME, SCHEMA_VERSION);
    }
}
