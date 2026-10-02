package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ismoyuan.opspilot.application.faultlab.FaultConfirmation;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1.SymptomBranch;
import io.github.ismoyuan.opspilot.application.faultlab.FaultTarget;
import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQueryInjector;
import io.github.ismoyuan.opspilot.application.faultlab.MysqlSlowQuerySettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * S2 注入器在真实 MySQL 8.4.11 与真实 Hikari Pool 上的行为（08 TASK-095、09 §44～§52、§59）：库中对象由 deploy/demo 的
 * demo-objects.sql 原样创建；“project-api”替身用自己的 HikariDataSource（上限 8）提供与 ShortLink Demo Profile 相同形状的刷新负载端点、
 * hikaricp/http.server.requests 指标与创建接口，慢存储过程与创建都经这个连接池，连接池争用、Performance Schema 摘要与创建退化都是真实的。
 * 完整 ShortLink 靶场的校准与端到端记录在 PROGRESS（B36）。
 */
class MysqlSlowQueryInjectorIntegrationTest {

    static final FaultTarget TARGET =
            new FaultTarget(51, "mysql-slow-query", "shortlink-platform", 6, "shortlink-mysql");

    static MySQLContainer mysql;
    static HikariDataSource pool;
    static HttpServer projectApi;
    static ExecutorService handlers;
    static ScheduledExecutorService load;
    static final HttpClient http = HttpClient.newHttpClient();
    static final AtomicLong createRequests = new AtomicLong();
    static final AtomicInteger inFlight = new AtomicInteger();
    static volatile boolean running;
    /** 让 hikaricp 指标返回 404（指标未注册／池标签不匹配）。 */
    static volatile boolean poolMetricsMissing;
    /** 让 http.server.requests 返回 404（还没有创建请求时的合法状态）。 */
    static volatile boolean createMetricMissing;

    static volatile int statementMillis;
    static final List<Thread> workers = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        mysql = new MySQLContainer("mysql:8.4.11")
                .withDatabaseName("link")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("../../deploy/demo/mysql/demo-objects.sql")),
                        "/demo/demo-objects.sql");
        mysql.start();
        try (Connection root = root();
                Statement statement = root.createStatement()) {
            statement.execute("CREATE TABLE t_link_goto_0 (id BIGINT PRIMARY KEY, full_short_url VARCHAR(128))");
            statement.execute("CREATE TABLE t_link_goto_1 (id BIGINT PRIMARY KEY, full_short_url VARCHAR(128))");
            statement.execute("CREATE TABLE t_link_create (id BIGINT AUTO_INCREMENT PRIMARY KEY, url VARCHAR(255))");
            // 调查只读账号的形状（06 §73）：能读 performance_schema，不能清理
            statement.execute("CREATE USER 'opspilot_readonly'@'%' IDENTIFIED BY 'readonly'");
            statement.execute("GRANT SELECT ON performance_schema.* TO 'opspilot_readonly'@'%'");
        }
        // 与 Compose 一次性初始化服务相同：mysql 客户端执行 deploy/demo/mysql/demo-objects.sql
        var applied = mysql.execInContainer(
                "sh", "-c", "MYSQL_PWD=" + mysql.getPassword() + " mysql -uroot < /demo/demo-objects.sql");
        assertThat(applied.getExitCode()).as(applied.getStderr()).isZero();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mysql.getJdbcUrl());
        config.setUsername("root");
        config.setPassword(mysql.getPassword());
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(30_000);
        pool = new HikariDataSource(config);

        projectApi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newCachedThreadPool();
        projectApi.setExecutor(handlers);
        projectApi.createContext("/actuator/snapshotworkload", MysqlSlowQueryInjectorIntegrationTest::workload);
        projectApi.createContext("/actuator/metrics/", MysqlSlowQueryInjectorIntegrationTest::metrics);
        projectApi.createContext("/api/short-link/v1/create", MysqlSlowQueryInjectorIntegrationTest::create);
        projectApi.start();

        // 持续创建负载：约 5 req/s
        load = Executors.newScheduledThreadPool(1);
        load.scheduleAtFixedRate(
                () -> http.sendAsync(
                        HttpRequest.newBuilder(base().resolve("/api/short-link/v1/create"))
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.discarding()),
                0,
                200,
                TimeUnit.MILLISECONDS);
    }

    @AfterAll
    static void stop() {
        running = false;
        if (load != null) {
            load.shutdownNow();
        }
        if (projectApi != null) {
            projectApi.stop(0);
            handlers.shutdownNow();
        }
        if (pool != null) {
            pool.close();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void realPoolContentionAndSlowStatementsConfirmTheGateAndResetClearsTheSummary() throws Exception {
        MysqlSlowQueryInjector injector = injector();

        var injection = injector.inject(TARGET);
        FaultConfirmation confirmation = injector.verifyInjected(TARGET);
        var gate = confirmation.mysqlSlowQueryGate();
        assertThat(confirmation.detectedAt()).isAfterOrEqualTo(injection.startedAt());
        // ACC-S2-001～005：经应用连接池的真实慢工作、active 饱和、≥ 2 次 pending、真实慢语句摘要、创建 LATENCY 退化
        assertThat(gate.maxActive()).isEqualTo(8);
        assertThat(gate.saturatedSamples()).isGreaterThanOrEqualTo(3);
        assertThat(gate.pendingSamples()).isGreaterThanOrEqualTo(2);
        assertThat(Math.max(gate.slowStatementAvgMs(), gate.slowStatementMaxMs()))
                .isGreaterThanOrEqualTo(2000);
        assertThat(gate.symptomBranch()).isIn(SymptomBranch.LATENCY, SymptomBranch.BOTH);
        assertThat(gate.faultP99Ms()).isGreaterThanOrEqualTo(Math.max(gate.baselineP99Ms() * 5, 2000));
        assertThat(digests()).anyMatch(d -> d.startsWith("CALL `refresh_link_statistics_snapshot`"));

        injector.reset(TARGET);
        assertThat(running).isFalse();
        assertThat(inFlight.get()).isZero();
        assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
        // 摘要已清理；Demo 控制账号自己的语句不进入统计（setup_actors），被调查方看不到控制痕迹
        assertThat(digests()).noneMatch(d -> d.startsWith("CALL `refresh_link_statistics_snapshot`"));
        // （本测试自身以 root 查询摘要表的语句会出现，只断言控制账号的清理与汇总读取不在其中）
        assertThat(digests()).noneMatch(d -> d.startsWith("TRUNCATE") || d.contains("SUM ( `COUNT_STAR` )"));
        // 再次 Reset：负载已停，只确认恢复
        injector.reset(TARGET);
    }

    /**
     * B36-R1：连接池指标缺失（404）不能当作无争用的 0——Preflight 不启动慢负载，Reset 不清理摘要也不报告恢复；创建计数尚未产生时的 404
     * 仍按 0 处理。
     */
    @Test
    void missingPoolMetricsBlockInjectionAndReset() throws Exception {
        MysqlSlowQueryInjector injector = injector();
        DemoMysqlSlowQueryEnvironment environment = environment();
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CALL refresh_link_statistics_snapshot(100)");
        }
        poolMetricsMissing = true;
        try {
            assertThatThrownBy(() -> environment.pool(java.time.Instant.now().plusSeconds(5)))
                    .hasMessage("Demo environment: ShortLink connection pool metrics are not available");
            // Preflight 要求摘要无残留：先以控制账号清理，再验证缺失指标时不启动负载
            environment.clearStatementSummary(java.time.Instant.now().plusSeconds(5));
            assertThatThrownBy(() -> injector.inject(TARGET))
                    .hasMessage("Demo environment: ShortLink connection pool metrics are not available");
            assertThat(running).isFalse();

            try (Connection connection = pool.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CALL refresh_link_statistics_snapshot(100)");
            }
            assertThatThrownBy(() -> injector.reset(TARGET))
                    .hasMessageContaining("connection pool metrics are not available");
            // 没有走到清理：摘要仍保留刚才的执行
            assertThat(digests()).anyMatch(d -> d.startsWith("CALL `refresh_link_statistics_snapshot`"));
        } finally {
            poolMetricsMissing = false;
        }
        createMetricMissing = true;
        try {
            assertThat(environment.createRequests(java.time.Instant.now().plusSeconds(5)))
                    .isZero();
        } finally {
            createMetricMissing = false;
        }
        environment.clearStatementSummary(java.time.Instant.now().plusSeconds(5));
    }

    /**
     * B36-R1：JDBC 调用整体受期限约束。透明 TCP 转发器让 MySQL 的每段应答延迟 180ms（握手与读取多段累积），500ms 期限内读取或清理摘要都在
     * 期限附近失败，而不是各阶段分别耗尽超时后才返回。
     */
    @Test
    void jdbcCallsEndAtTheirDeadlineThroughASlowLink() throws Exception {
        try (SlowForwarder forwarder = new SlowForwarder(mysql.getHost(), mysql.getMappedPort(3306), 180)) {
            DemoMysqlSlowQueryEnvironment environment = environment("jdbc:mysql://127.0.0.1:" + forwarder.port() + "/");
            for (Runnable call : List.<Runnable>of(
                    () -> environment.slowStatement(java.time.Instant.now().plusMillis(500)),
                    () -> environment.clearStatementSummary(
                            java.time.Instant.now().plusMillis(500)))) {
                long started = System.nanoTime();
                assertThatThrownBy(call::run)
                        .hasMessage("Demo environment: the MySQL statement summary did not answer before the deadline");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(800));
            }
        }
    }

    /**
     * B36-R2：超时返回之后，后台连接即使随后建立成功也不再执行 SQL。摘要中先留一条真实 CALL 作为标记；经慢链路清理摘要在 500ms 期限
     * 失败后，慢链路继续保持 3 秒（足够完成握手与执行），标记仍在——没有迟到的 TRUNCATE。
     */
    @Test
    void aTimedOutClearDoesNotRunLater() throws Exception {
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CALL refresh_link_statistics_snapshot(100)");
        }
        assertThat(digests()).anyMatch(d -> d.startsWith("CALL `refresh_link_statistics_snapshot`"));
        try (SlowForwarder forwarder = new SlowForwarder(mysql.getHost(), mysql.getMappedPort(3306), 180)) {
            DemoMysqlSlowQueryEnvironment environment = environment("jdbc:mysql://127.0.0.1:" + forwarder.port() + "/");
            assertThatThrownBy(() -> environment.clearStatementSummary(
                            java.time.Instant.now().plusMillis(500)))
                    .hasMessage("Demo environment: the MySQL statement summary did not answer before the deadline");
            Thread.sleep(3000);
            assertThat(digests()).anyMatch(d -> d.startsWith("CALL `refresh_link_statistics_snapshot`"));
        }
        environment().clearStatementSummary(java.time.Instant.now().plusSeconds(5));
    }

    /** 透明 TCP 转发器：服务端到客户端的每段数据延迟 {@code delayMillis} 再转发。 */
    static final class SlowForwarder implements AutoCloseable {

        private final java.net.ServerSocket server = new java.net.ServerSocket(0);
        private final List<java.net.Socket> sockets = new java.util.concurrent.CopyOnWriteArrayList<>();

        SlowForwarder(String host, int port, long delayMillis) throws IOException {
            Thread.ofPlatform().daemon().start(() -> {
                while (!server.isClosed()) {
                    try {
                        java.net.Socket client = server.accept();
                        java.net.Socket upstream = new java.net.Socket(host, port);
                        sockets.add(client);
                        sockets.add(upstream);
                        pump(client, upstream, 0);
                        pump(upstream, client, delayMillis);
                    } catch (IOException ex) {
                        return;
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        private static void pump(java.net.Socket from, java.net.Socket to, long delayMillis) {
            Thread.ofPlatform().daemon().start(() -> {
                byte[] buffer = new byte[8192];
                try (var in = from.getInputStream();
                        var out = to.getOutputStream()) {
                    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                        if (delayMillis > 0) {
                            Thread.sleep(delayMillis);
                        }
                        out.write(buffer, 0, n);
                        out.flush();
                    }
                } catch (IOException | InterruptedException ignored) {
                    // 连接结束
                }
            });
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (java.net.Socket socket : sockets) {
                socket.close();
            }
        }
    }

    /** 09 §52：只有 Demo 控制账号能清理语句摘要；调查只读账号不能。 */
    @Test
    void onlyTheDemoControlAccountCanClearTheStatementSummary() throws Exception {
        try (Connection readonly = DriverManager.getConnection(serverUrl(), "opspilot_readonly", "readonly");
                Statement statement = readonly.createStatement()) {
            assertThatThrownBy(() ->
                            statement.execute("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("denied");
        }
        try (Connection control =
                        DriverManager.getConnection(serverUrl(), "opspilot_fault_control", "fault_control_local_only");
                Statement statement = control.createStatement()) {
            statement.execute("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest");
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM link.t_link_create"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private DemoMysqlSlowQueryEnvironment environment() {
        return environment("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/");
    }

    private DemoMysqlSlowQueryEnvironment environment(String controlJdbcUrl) {
        FaultLabProperties.MysqlSlowQuery base = config();
        return new DemoMysqlSlowQueryEnvironment(
                new FaultLabProperties.MysqlSlowQuery(
                        true,
                        base.systemKey(),
                        null,
                        base.managementEndpoint(),
                        base.shortlinkEndpoint(),
                        null,
                        controlJdbcUrl,
                        base.controlUsername(),
                        base.controlPassword(),
                        base.createUsername(),
                        base.createGroupId(),
                        base.probeTimeout(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                Clock.systemUTC());
    }

    private MysqlSlowQueryInjector injector() {
        FaultLabProperties.MysqlSlowQuery config = config();
        return injectorWith(config);
    }

    private static FaultLabProperties.MysqlSlowQuery config() {
        return new FaultLabProperties.MysqlSlowQuery(
                true,
                "shortlink-platform",
                null,
                base(),
                base(),
                null,
                "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/",
                "opspilot_fault_control",
                "fault_control_local_only",
                "opspilot-demo",
                "opspilotdemo",
                Duration.ofSeconds(10),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private MysqlSlowQueryInjector injectorWith(FaultLabProperties.MysqlSlowQuery config) {
        MysqlSlowQuerySettings settings = new MysqlSlowQuerySettings(
                8,
                3000,
                Duration.ofSeconds(3),
                Duration.ofMillis(500),
                1,
                2.0,
                0.01,
                7,
                3,
                2,
                Duration.ofMillis(2000),
                5.0,
                Duration.ofMillis(2000),
                0.05,
                0.05,
                Duration.ofSeconds(30),
                Duration.ofMillis(500),
                Duration.ofSeconds(60));
        Clock clock = Clock.systemUTC();
        return new MysqlSlowQueryInjector(
                new DemoMysqlSlowQueryEnvironment(config, clock),
                settings,
                config.systemKey(),
                config.targetResourceKeyOrDefault(),
                clock);
    }

    private static URI base() {
        return URI.create("http://127.0.0.1:" + projectApi.getAddress().getPort());
    }

    /** 不指定默认库的地址（受限账号对 link 库没有权限）。 */
    private static String serverUrl() {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
    }

    private static Connection root() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
    }

    private static List<String> digests() throws SQLException {
        List<String> digests = new ArrayList<>();
        try (Connection root = root();
                Statement statement = root.createStatement();
                ResultSet rs = statement.executeQuery("SELECT DIGEST_TEXT FROM"
                        + " performance_schema.events_statements_summary_by_digest WHERE DIGEST_TEXT IS NOT NULL")) {
            while (rs.next()) {
                digests.add(rs.getString(1));
            }
        }
        return digests;
    }

    // ---------------------------------------------------------------- project-api 替身

    private static synchronized void workload(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "POST" -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int count = Integer.parseInt(body.replaceAll(".*\"workers\":(\\d+).*", "$1"));
                statementMillis = Integer.parseInt(body.replaceAll(".*\"statementMillis\":(\\d+).*", "$1"));
                if (!running) {
                    running = true;
                    workers.clear();
                    for (int i = 0; i < count; i++) {
                        workers.add(Thread.ofPlatform().daemon().start(MysqlSlowQueryInjectorIntegrationTest::refresh));
                    }
                }
            }
            case "DELETE" -> {
                running = false;
                for (Thread worker : workers) {
                    try {
                        worker.join(statementMillis + 10_000L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            default -> {
                // GET：状态
            }
        }
        respond(exchange, 200, "{\"running\":" + running + ",\"inFlight\":" + inFlight.get() + "}");
    }

    private static void refresh() {
        while (running) {
            inFlight.incrementAndGet();
            try (Connection connection = pool.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CALL refresh_link_statistics_snapshot(" + statementMillis + ")");
            } catch (SQLException ignored) {
                // 与 ShortLink 相同：失败后继续
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    private static void metrics(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ((poolMetricsMissing && path.contains("hikaricp"))
                || (createMetricMissing && path.contains("http.server"))) {
            respond(exchange, 404, "{}");
            return;
        }
        var mx = pool.getHikariPoolMXBean();
        double value;
        String statistic = "VALUE";
        if (path.endsWith("hikaricp.connections.active")) {
            value = mx.getActiveConnections();
        } else if (path.endsWith("hikaricp.connections.pending")) {
            value = mx.getThreadsAwaitingConnection();
        } else if (path.endsWith("hikaricp.connections.max")) {
            value = 8;
        } else {
            value = createRequests.get();
            statistic = "COUNT";
        }
        respond(exchange, 200, "{\"measurements\":[{\"statistic\":\"" + statistic + "\",\"value\":" + value + "}]}");
    }

    private static void create(HttpExchange exchange) throws IOException {
        createRequests.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        try (Connection connection = pool.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO t_link_create (url) VALUES ('demo')");
            respond(exchange, 200, "{\"code\":\"0\"}");
        } catch (SQLException ex) {
            respond(exchange, 200, "{\"code\":\"B000001\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
