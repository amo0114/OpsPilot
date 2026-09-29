package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.DatabaseInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InspectionType;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.provider.ObserveProvider;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.ConnectionSummary;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LockWait;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LockWaits;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LongestConnection;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.ServerSummary;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.SlowQuery;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.StateCount;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.binding.MySqlResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.connection.MySqlConnectionConfigV1;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * database.inspect 的 MySQL Provider（08 TASK-055、06 §68～§81）。AI 只给固定 inspectionType 与 limit（1～20，缺省 10），不存在任意 SQL
 * （CAP-INV-003/010）：四种检查各对应一条固定、带绑定参数的只读 SQL，只读 performance_schema 的状态与统计，从不读取业务表、
 * PROCESSLIST_INFO（可能含字面量的 SQL 原文）或查询结果数据；慢语句只取 DIGEST_TEXT（规范化，字面量为 ?），范围限定为 Binding 的
 * databaseName。每次调用建立一个只读会话（setReadOnly），不复用连接池、不自动重连（Connector/J 默认），连接/读取超时与语句超时
 * 均取自调用期限。
 *
 * <p>调查账号所需最小权限：SELECT ON performance_schema.*，以及查看其他会话所需的 PROCESS（06 §73；部署落地属 TASK-105）。
 */
final class MySqlDatabaseInspectProvider implements ObserveProvider {

    static final int DEFAULT_LIMIT = 10;
    static final int DEFAULT_PORT = 3306;
    /** LOCK_WAITS 一次最多读取的等待关系；达到上限时 waitingCount 为下限。 */
    static final int LOCK_WAIT_SCAN_LIMIT = 1000;

    static final String SERVER_SUMMARY_SQL =
            "SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status"
                    + " WHERE VARIABLE_NAME IN ('Threads_connected', 'Threads_running', 'Questions', 'Slow_queries', 'Uptime',"
                    + " 'Innodb_buffer_pool_pages_total', 'Innodb_buffer_pool_pages_free')";

    static final String CONNECTION_SUMMARY_SQL =
            "SELECT PROCESSLIST_COMMAND, PROCESSLIST_STATE, PROCESSLIST_TIME FROM performance_schema.threads"
                    + " WHERE TYPE = 'FOREGROUND' AND PROCESSLIST_ID IS NOT NULL AND PROCESSLIST_ID <> CONNECTION_ID()"
                    + " AND PROCESSLIST_DB = ?";

    static final String SLOW_QUERIES_SQL = "SELECT DIGEST, DIGEST_TEXT, COUNT_STAR, AVG_TIMER_WAIT, MAX_TIMER_WAIT,"
            + " SUM_ROWS_EXAMINED, UNIX_TIMESTAMP(LAST_SEEN)"
            + " FROM performance_schema.events_statements_summary_by_digest"
            + " WHERE SCHEMA_NAME = ? AND DIGEST IS NOT NULL AND DIGEST_TEXT IS NOT NULL AND COUNT_STAR > 0"
            + " ORDER BY AVG_TIMER_WAIT DESC, DIGEST LIMIT ?";

    /**
     * 等待时长取自等待事务的真实开始等锁时刻（information_schema.INNODB_TRX.TRX_WAIT_STARTED，需 PROCESS），不用会话的 PROCESSLIST_TIME
     * ——后者包含语句开始等锁之前已执行的时间（B17-R1）。按毫秒计算后取整秒；等待已结束（事务不再处于等待）的关系不计入。
     */
    static final String LOCK_WAITS_SQL = "SELECT COALESCE(rt.PROCESSLIST_ID, w.REQUESTING_THREAD_ID),"
            + " COALESCE(bt.PROCESSLIST_ID, w.BLOCKING_THREAD_ID),"
            + " GREATEST(0, TIMESTAMPDIFF(MICROSECOND, trx.TRX_WAIT_STARTED, NOW(6))) DIV 1000000 AS wait_seconds,"
            + " rl.OBJECT_SCHEMA, rl.OBJECT_NAME"
            + " FROM performance_schema.data_lock_waits w"
            + " JOIN performance_schema.data_locks rl"
            + " ON rl.ENGINE = w.ENGINE AND rl.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID"
            + " JOIN information_schema.INNODB_TRX trx"
            + " ON trx.TRX_ID = w.REQUESTING_ENGINE_TRANSACTION_ID AND trx.TRX_WAIT_STARTED IS NOT NULL"
            + " LEFT JOIN performance_schema.threads rt ON rt.THREAD_ID = w.REQUESTING_THREAD_ID"
            + " LEFT JOIN performance_schema.threads bt ON bt.THREAD_ID = w.BLOCKING_THREAD_ID"
            + " WHERE rl.OBJECT_SCHEMA = ?"
            + " ORDER BY wait_seconds DESC LIMIT " + LOCK_WAIT_SCAN_LIMIT;

    private static final BigDecimal PICOS_PER_MILLI = BigDecimal.valueOf(1_000_000_000L);

    private final ProviderAuthentication authentication;
    private final Clock clock;

    MySqlDatabaseInspectProvider(ProviderAuthentication authentication, Clock clock) {
        this.authentication = authentication;
        this.clock = clock;
    }

    @Override
    public CapabilityKey capability() {
        return CapabilityKey.DATABASE_INSPECT;
    }

    @Override
    public ProviderOutcome fetch(AdmittedInvocation invocation, Instant deadline) {
        try {
            if (!(invocation.provider().selector() instanceof MySqlResourceBindingV1 selector)) {
                throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Resource binding is not a MySQL binding");
            }
            DatabaseInspectArgumentsV1 arguments = (DatabaseInspectArgumentsV1) invocation.arguments();
            int limit = arguments.limit() == null ? DEFAULT_LIMIT : arguments.limit();
            DataSourceConnection source = invocation.provider().connection();
            MySqlConnectionConfigV1 config =
                    authentication.config(source, MySqlConnectionConfigV1.SCHEMA_NAME, MySqlConnectionConfigV1.class);
            String url = jdbcUrl(source.endpoint());
            String password = authentication.credential(source);
            StringBuilder raw = new StringBuilder("inspection ")
                    .append(arguments.inspectionType())
                    .append('\n');
            DatabaseInspectResultV1 result;
            try (Connection connection = open(url, config.username(), password, deadline)) {
                result = switch (arguments.inspectionType()) {
                    case SERVER_SUMMARY -> server(connection, deadline, raw);
                    case CONNECTION_SUMMARY -> connections(connection, selector.databaseName(), deadline, raw);
                    case SLOW_QUERIES -> slowQueries(connection, selector.databaseName(), limit, deadline, raw);
                    case LOCK_WAITS -> lockWaits(connection, selector.databaseName(), limit, deadline, raw);
                };
            } catch (SQLException ex) {
                throw translate(ex);
            } catch (IllegalArgumentException ex) {
                throw new ProviderCallException(
                        ErrorCode.PROVIDER_RESPONSE_INVALID, "MySQL statistics are inconsistent");
            }
            return new ProviderOutcome.Fetched(result, raw.toString(), clock.instant());
        } catch (ProviderCallException ex) {
            return ex.outcome();
        }
    }

    private DatabaseInspectResultV1 server(Connection connection, Instant deadline, StringBuilder raw)
            throws SQLException {
        Map<String, Long> status = new HashMap<>();
        try (PreparedStatement statement = prepare(connection, SERVER_SUMMARY_SQL, deadline);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString(1);
                try {
                    status.put(name, Long.parseLong(rows.getString(2)));
                } catch (NumberFormatException ignored) {
                    // 非数值状态不采用
                }
                raw.append(name).append(':').append(rows.getString(2)).append('\n');
            }
        }
        Long total = status.get("Innodb_buffer_pool_pages_total");
        Long free = status.get("Innodb_buffer_pool_pages_free");
        Double usage = total == null || free == null || total == 0 ? null : (total - free) / (double) total;
        return new DatabaseInspectResultV1(
                InspectionType.SERVER_SUMMARY,
                new ServerSummary(
                        required(status, "Threads_connected"),
                        required(status, "Threads_running"),
                        required(status, "Questions"),
                        required(status, "Slow_queries"),
                        required(status, "Uptime"),
                        usage),
                null,
                null,
                null);
    }

    private DatabaseInspectResultV1 connections(
            Connection connection, String database, Instant deadline, StringBuilder raw) throws SQLException {
        long total = 0;
        long running = 0;
        Map<String, Long> states = new LinkedHashMap<>();
        LongestConnection longest = null;
        try (PreparedStatement statement = prepare(connection, CONNECTION_SUMMARY_SQL, deadline)) {
            statement.setString(1, database);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String command = rows.getString(1);
                    String state = blankToNull(rows.getString(2));
                    long time = Math.max(0, rows.getLong(3));
                    total++;
                    states.merge(state == null ? command : state, 1L, Long::sum);
                    boolean active = command != null && !command.equals("Sleep") && !command.equals("Daemon");
                    if (active) {
                        running++;
                        if (longest == null || time > longest.timeSeconds()) {
                            longest = new LongestConnection(time, command, state);
                        }
                    }
                    raw.append(command)
                            .append('\t')
                            .append(state)
                            .append('\t')
                            .append(time)
                            .append('\n');
                }
            }
        }
        List<StateCount> distribution = new ArrayList<>();
        states.forEach((name, count) -> {
            if (name != null && !name.isBlank()) {
                distribution.add(new StateCount(name, count));
            }
        });
        return new DatabaseInspectResultV1(
                InspectionType.CONNECTION_SUMMARY,
                null,
                new ConnectionSummary(total, running, distribution, longest),
                null,
                null);
    }

    private DatabaseInspectResultV1 slowQueries(
            Connection connection, String database, int limit, Instant deadline, StringBuilder raw)
            throws SQLException {
        List<SlowQuery> queries = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection, SLOW_QUERIES_SQL, deadline)) {
            statement.setString(1, database);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long count = rows.getLong(3);
                    double average = millis(rows.getBigDecimal(4));
                    double max = Math.max(average, millis(rows.getBigDecimal(5)));
                    BigDecimal rowsExamined = rows.getBigDecimal(6);
                    BigDecimal lastSeen = rows.getBigDecimal(7);
                    queries.add(new SlowQuery(
                            rows.getString(1),
                            rows.getString(2),
                            count,
                            average,
                            max,
                            rowsExamined == null ? null : rowsExamined.doubleValue() / count,
                            lastSeen == null
                                    ? null
                                    : Instant.ofEpochMilli(
                                            lastSeen.movePointRight(3).longValue())));
                    raw.append(rows.getString(1))
                            .append('\t')
                            .append(count)
                            .append('\t')
                            .append(average)
                            .append('\t')
                            .append(max)
                            .append('\t')
                            .append(rows.getString(2).replace('\n', ' '))
                            .append('\n');
                }
            }
        }
        return new DatabaseInspectResultV1(InspectionType.SLOW_QUERIES, null, null, queries, null);
    }

    private DatabaseInspectResultV1 lockWaits(
            Connection connection, String database, int limit, Instant deadline, StringBuilder raw)
            throws SQLException {
        List<LockWait> all = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection, LOCK_WAITS_SQL, deadline)) {
            statement.setString(1, database);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String object = rows.getString(5) == null ? null : rows.getString(4) + "." + rows.getString(5);
                    LockWait wait =
                            new LockWait(rows.getLong(1), rows.getLong(2), Math.max(0, rows.getLong(3)), object);
                    all.add(wait);
                    raw.append(wait.waitingThreadId())
                            .append('\t')
                            .append(wait.blockingThreadId())
                            .append('\t')
                            .append(wait.waitSeconds())
                            .append('\t')
                            .append(object)
                            .append('\n');
                }
            }
        }
        Long longest = all.stream().mapToLong(LockWait::waitSeconds).max().stream()
                .boxed()
                .findFirst()
                .orElse(null);
        return new DatabaseInspectResultV1(
                InspectionType.LOCK_WAITS,
                null,
                null,
                null,
                new LockWaits(all.size(), longest, all.subList(0, Math.min(limit, all.size()))));
    }

    private Connection open(String url, String username, String password, Instant deadline) throws SQLException {
        int remaining = remainingMillis(deadline);
        Properties properties = new Properties();
        properties.setProperty("user", username);
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("connectTimeout", Integer.toString(remaining));
        properties.setProperty("socketTimeout", Integer.toString(remaining));
        properties.setProperty("allowLoadLocalInfile", "false");
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("autoDeserialize", "false");
        properties.setProperty("allowPublicKeyRetrieval", "false");
        properties.setProperty("autoReconnect", "false");
        properties.setProperty("readOnlyPropagatesToServer", "true");
        Connection connection = DriverManager.getConnection(url, properties);
        try {
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException ex) {
            connection.close();
            throw ex;
        }
    }

    private PreparedStatement prepare(Connection connection, String sql, Instant deadline) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout((int) Math.max(1, (remainingMillis(deadline) + 999) / 1000));
        return statement;
    }

    /**
     * 受信端点须为 mysql://host[:port]，不含用户信息、库名、查询或片段（凭据只经 credentialRef，库名来自 Binding）。
     *
     * @throws ProviderCallException INVALID_BINDING
     */
    static String jdbcUrl(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException ex) {
            throw new ProviderCallException(ErrorCode.INVALID_BINDING, "MySQL endpoint is not a valid URI");
        }
        String path = uri.getRawPath();
        if (!"mysql".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || (path != null && !path.isEmpty() && !path.equals("/"))) {
            throw new ProviderCallException(
                    ErrorCode.INVALID_BINDING, "MySQL endpoint must be mysql://host:port without credentials");
        }
        return "jdbc:mysql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? DEFAULT_PORT : uri.getPort()) + "/";
    }

    /** JDBC 失败按 SQLState/厂商码与原因映射；文案固定，不含服务端错误文本（可能带账号或主机名）。 */
    static ProviderCallException translate(SQLException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLTimeoutException || t instanceof SocketTimeoutException) {
                return new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
            }
        }
        int code = ex.getErrorCode();
        String state = ex.getSQLState() == null ? "" : ex.getSQLState();
        if (code == 1045 || state.equals("28000")) {
            return new ProviderCallException(ErrorCode.AUTHENTICATION_FAILED, "MySQL rejected the credential");
        }
        if (code == 1142 || code == 1143 || code == 1044 || code == 1227) {
            return new ProviderCallException(
                    ErrorCode.AUTHORIZATION_DENIED, "MySQL denied the inspection for this account");
        }
        if (code == 3024) {
            return new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
        }
        if (state.startsWith("08")) {
            return new ProviderCallException(ErrorCode.CONNECTION_FAILED, "Provider could not be reached");
        }
        return new ProviderCallException(ErrorCode.QUERY_REJECTED, "MySQL rejected the inspection query");
    }

    private int remainingMillis(Instant deadline) {
        long millis = Duration.between(clock.instant(), deadline).toMillis();
        if (millis <= 0) {
            throw new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
        }
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private static long required(Map<String, Long> status, String name) {
        Long value = status.get(name);
        if (value == null || value < 0) {
            throw new ProviderCallException(ErrorCode.PROVIDER_RESPONSE_INVALID, "MySQL status is incomplete");
        }
        return value;
    }

    private static double millis(BigDecimal picoseconds) {
        return picoseconds == null ? 0 : picoseconds.divide(PICOS_PER_MILLI).doubleValue();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
