package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static io.github.ismoyuan.opspilot.infrastructure.faultlab.DemoTrafficObserver.CALL_TIMEOUT;
import static io.github.ismoyuan.opspilot.infrastructure.faultlab.DemoTrafficObserver.bounded;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RedirectProbe;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQueryEnvironment;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S2 Demo 靶场的真实访问（08 TASK-095）：project-api 管理端口上的 Demo-only 刷新负载端点与 Spring Boot 标准指标
 * （hikaricp.connections.*、http.server.requests），Demo 控制凭证读取/清理 Performance Schema 语句摘要（JDBC），创建接口探测。控制请求
 * 只走管理端口与 Demo 控制账号（其会话不进入 Performance Schema 统计），不经业务日志。
 */
final class DemoMysqlSlowQueryEnvironment implements MysqlSlowQueryEnvironment {

    static final String WORKLOAD_PATH = "/actuator/snapshotworkload";

    static final String CREATE_PATH = "/api/short-link/v1/create";

    /** 慢存储过程的语句摘要（Performance Schema 规范化后的文本）。 */
    static final String SLOW_DIGEST_PREFIX = "CALL `refresh_link_statistics_snapshot`";

    private final FaultLabProperties.MysqlSlowQuery config;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;
    private final AtomicLong nextCreate = new AtomicLong();
    private final ExecutorService jdbcThreads = Executors.newVirtualThreadPerTaskExecutor();

    DemoMysqlSlowQueryEnvironment(FaultLabProperties.MysqlSlowQuery config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(config.probeTimeoutOrDefault())
                .build();
    }

    @Override
    public WorkloadState workload(Instant deadline) {
        return workloadState(management("GET", WORKLOAD_PATH, null, bounded(clock, deadline, CALL_TIMEOUT)));
    }

    @Override
    public void startWorkload(int workers, int statementMillis, Instant deadline) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workers", workers);
        body.put("statementMillis", statementMillis);
        WorkloadState state = workloadState(management(
                "POST", WORKLOAD_PATH, json.writeValueAsString(body), bounded(clock, deadline, CALL_TIMEOUT)));
        if (!state.running()) {
            throw new FaultInjectionException("Demo environment: the snapshot workload did not start");
        }
    }

    @Override
    public WorkloadState stopWorkload(Instant deadline) {
        // project-api 等进行中的刷新结束后才应答（最多一次刷新时长加 10 秒）
        Duration limit = CALL_TIMEOUT.plus(Duration.ofMillis(config.statementMillisOrDefault()));
        return workloadState(management("DELETE", WORKLOAD_PATH, null, bounded(clock, deadline, limit)));
    }

    @Override
    public PoolState pool(Instant deadline) {
        Instant bounded = bounded(clock, deadline, CALL_TIMEOUT);
        // 连接池指标是必需的：缺失（404）不能当作“无争用”的 0（B36-R1）
        PoolState pool = new PoolState(
                (int) metric("hikaricp.connections.active", poolTag(), "VALUE", bounded, false),
                (int) metric("hikaricp.connections.pending", poolTag(), "VALUE", bounded, false),
                (int) metric("hikaricp.connections.max", poolTag(), "VALUE", bounded, false));
        if (pool.max() < 1 || pool.active() < 0 || pool.active() > pool.max() || pool.pending() < 0) {
            throw new FaultInjectionException("Demo environment: ShortLink connection pool metrics are not valid");
        }
        return pool;
    }

    @Override
    public long createRequests(Instant deadline) {
        return (long) metric(
                "http.server.requests",
                "tag=" + encode("uri:" + CREATE_PATH) + "&tag=" + encode("method:POST"),
                "COUNT",
                bounded(clock, deadline, CALL_TIMEOUT),
                true);
    }

    @Override
    public SlowStatement slowStatement(Instant deadline) {
        Instant bounded = bounded(clock, deadline, CALL_TIMEOUT);
        String sql =
                "SELECT COALESCE(SUM(COUNT_STAR), 0), COALESCE(SUM(SUM_TIMER_WAIT), 0), COALESCE(MAX(MAX_TIMER_WAIT), 0)"
                        + " FROM performance_schema.events_statements_summary_by_digest WHERE DIGEST_TEXT LIKE '"
                        + SLOW_DIGEST_PREFIX + "%'";
        return jdbc(bounded, statement -> {
            try (ResultSet rs = statement.executeQuery(sql)) {
                rs.next();
                long executions = rs.getLong(1);
                // 计时单位为皮秒
                long avgMillis = executions == 0 ? 0 : rs.getBigDecimal(2).longValue() / executions / 1_000_000_000L;
                return new SlowStatement(
                        executions, avgMillis, rs.getBigDecimal(3).longValue() / 1_000_000_000L);
            }
        });
    }

    @Override
    public void clearStatementSummary(Instant deadline) {
        jdbc(bounded(clock, deadline, CALL_TIMEOUT), statement -> {
            statement.execute("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest");
            return null;
        });
    }

    @Override
    public RedirectProbe probeCreate(Instant deadline) {
        Instant bounded = bounded(clock, deadline, config.probeTimeoutOrDefault());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "originUrl",
                "http://opspilot-demo.invalid/create/" + System.currentTimeMillis() + "-"
                        + nextCreate.incrementAndGet());
        body.put("gid", config.createGroupId());
        body.put("createdType", 1);
        body.put("validDateType", 0);
        body.put("describe", "demo");
        long started = System.nanoTime();
        boolean succeeded;
        try {
            HttpResponse<byte[]> response = BoundedHttp.send(
                    http,
                    HttpRequest.newBuilder(config.shortlinkEndpoint().resolve(CREATE_PATH))
                            .header("Content-Type", "application/json")
                            .header("username", config.createUsername())
                            .header("User-Agent", DemoTrafficObserver.USER_AGENT)
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))),
                    bounded,
                    clock,
                    "ShortLink");
            succeeded = response.statusCode() == 200
                    && "0".equals(json.readTree(response.body()).path("code").asString(null));
        } catch (FaultInjectionException | JacksonException ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new FaultInjectionException("Fault lab action was interrupted");
            }
            succeeded = false;
        }
        return new RedirectProbe(succeeded, Duration.ofNanos(System.nanoTime() - started));
    }

    private String poolTag() {
        return "tag=" + encode("pool:" + config.poolNameOrDefault());
    }

    /**
     * Actuator 指标的一个统计量。
     *
     * @param absentIsZero 指标尚未产生（404）时是否按 0 处理：只适用于计数器（例如还没有创建请求），连接池指标缺失必须失败
     */
    private double metric(String name, String query, String statistic, Instant deadline, boolean absentIsZero) {
        HttpResponse<byte[]> response = BoundedHttp.send(
                http,
                HttpRequest.newBuilder(config.managementEndpoint().resolve("/actuator/metrics/" + name + "?" + query))
                        .GET(),
                deadline,
                clock,
                "ShortLink management");
        if (response.statusCode() == 404) {
            if (absentIsZero) {
                return 0;
            }
            throw new FaultInjectionException("Demo environment: ShortLink connection pool metrics are not available");
        }
        if (response.statusCode() != 200) {
            throw new FaultInjectionException("Demo environment: ShortLink metrics are not available");
        }
        try {
            for (JsonNode measurement : json.readTree(response.body()).path("measurements")) {
                if (statistic.equals(measurement.path("statistic").asString(null))
                        && measurement.path("value").isNumber()) {
                    return measurement.path("value").asDouble();
                }
            }
        } catch (JacksonException ex) {
            // 下方统一报告
        }
        throw new FaultInjectionException("Demo environment: ShortLink metrics are not valid");
    }

    private HttpResponse<byte[]> management(String method, String path, String body, Instant deadline) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        config.managementEndpoint().resolve(path))
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        HttpResponse<byte[]> response = BoundedHttp.send(http, request, deadline, clock, "ShortLink management");
        if (response.statusCode() != 200) {
            throw new FaultInjectionException("Demo environment: the snapshot workload endpoint rejected the request");
        }
        return response;
    }

    private WorkloadState workloadState(HttpResponse<byte[]> response) {
        try {
            JsonNode root = json.readTree(response.body());
            if (!root.path("running").isBoolean() || !root.path("inFlight").isIntegralNumber()) {
                throw invalidWorkload();
            }
            return new WorkloadState(
                    root.path("running").asBoolean(), root.path("inFlight").asInt());
        } catch (JacksonException ex) {
            throw invalidWorkload();
        }
    }

    private interface JdbcAction<T> {
        T run(Statement statement) throws SQLException;
    }

    /**
     * Demo 控制账号的一次 JDBC 操作，整体受期限约束（B36-R1）：连接、握手、执行与读取在单独线程中完成，调用方只等剩余时间；到期即中止
     * 连接（{@link Connection#abort}，关闭底层套接字）并失败，不把剩余预算重新发给后续阶段。驱动的连接/读超时只作为后台线程的兜底。
     */
    private <T> T jdbc(Instant deadline, JdbcAction<T> action) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (!remaining.isPositive()) {
            throw DemoTrafficObserver.deadlineReached();
        }
        Properties properties = new Properties();
        properties.setProperty("user", config.controlUsername());
        if (config.controlPassword() != null) {
            properties.setProperty("password", config.controlPassword());
        }
        properties.setProperty("connectTimeout", Long.toString(Math.max(1, remaining.toMillis())));
        properties.setProperty("socketTimeout", Long.toString(Math.max(1, remaining.toMillis())));
        JdbcHandoff handoff = new JdbcHandoff();
        CompletableFuture<T> call = CompletableFuture.supplyAsync(
                () -> {
                    try (Connection connection = DriverManager.getConnection(config.controlJdbcUrl(), properties)) {
                        // 连接建立无法中断：建立之后先与调用方交接，已取消就不再执行任何 SQL（B36-R2）
                        if (!handoff.start(connection)) {
                            throw new CancellationException("the call ended before the connection was ready");
                        }
                        try (Statement statement = connection.createStatement()) {
                            return action.run(statement);
                        }
                    } catch (SQLException ex) {
                        throw new CompletionException(ex);
                    }
                },
                jdbcThreads);
        try {
            return call.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            handoff.cancel();
            call.cancel(true);
            throw new FaultInjectionException(
                    "Demo environment: the MySQL statement summary did not answer before the deadline");
        } catch (ExecutionException ex) {
            throw new FaultInjectionException("Demo environment: the MySQL statement summary is not accessible");
        } catch (InterruptedException ex) {
            handoff.cancel();
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new FaultInjectionException("Fault lab action was interrupted");
        }
    }

    /**
     * 调用方与后台 JDBC 线程的交接：两者在同一把锁下决定“开始执行 SQL”与“取消”的先后。取消在前，后台线程拿到连接后不再执行任何
     * 语句；执行在前，取消时中止连接（关闭底层套接字）。因此调用返回超时之后不会再开始新的 SQL。
     */
    static final class JdbcHandoff {

        private Connection started;
        private boolean cancelled;

        /** @return 是否可以开始执行；已取消时返回 false（调用方随后关闭连接） */
        synchronized boolean start(Connection connection) {
            if (cancelled) {
                return false;
            }
            started = connection;
            return true;
        }

        synchronized void cancel() {
            cancelled = true;
            abort(started);
        }
    }

    /** 中止已开始执行 SQL 的连接。 */
    private static void abort(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException ignored) {
            // 已关闭
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static FaultInjectionException invalidWorkload() {
        return new FaultInjectionException("Demo environment: the snapshot workload status is not valid");
    }
}
