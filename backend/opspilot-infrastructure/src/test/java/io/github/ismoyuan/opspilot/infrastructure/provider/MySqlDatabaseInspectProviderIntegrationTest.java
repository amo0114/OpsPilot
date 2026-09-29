package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.DatabaseInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InspectionType;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.LockWait;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.SlowQuery;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1.StateCount;
import io.github.ismoyuan.opspilot.application.secret.SecretNotFoundException;
import io.github.ismoyuan.opspilot.application.secret.SecretResolver;
import io.github.ismoyuan.opspilot.application.secret.SecretValue;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.binding.MySqlResourceBindingV1;
import io.github.ismoyuan.opspilot.infrastructure.schema.SchemaCodecs;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 08 TASK-055：真实 MySQL（8.4.11）。调查账号只有 SELECT ON performance_schema.* 与 PROCESS；四种固定检查读取真实状态、真实连接、
 * 真实语句 digest（规范化，字面量为 ?）与真实行锁等待，从不读取业务表数据；认证失败、权限不足、非法端点与超时分别映射。
 */
@Testcontainers
class MySqlDatabaseInspectProviderIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    static final Map<String, String> SECRETS = Map.of(
            "env://OPSPILOT_MYSQL_RO", "ro-pass",
            "env://OPSPILOT_MYSQL_NOPS", "nops-pass",
            "env://OPSPILOT_MYSQL_WRONG", "not-the-password");

    static final SecretResolver RESOLVER = reference -> {
        String value = SECRETS.get(reference);
        if (value == null) {
            throw new SecretNotFoundException(SecretNotFoundException.Reason.NOT_FOUND, "missing " + reference);
        }
        return new SecretValue(value);
    };

    static final List<Connection> APP = new ArrayList<>();

    @BeforeAll
    static void prepareDatabase() throws Exception {
        try (Connection root = root("");
                Statement sql = root.createStatement()) {
            sql.execute("CREATE DATABASE shortlink");
            sql.execute(
                    "CREATE TABLE shortlink.t_link (id INT PRIMARY KEY, short_uri VARCHAR(64), target VARCHAR(255))");
            sql.execute("INSERT INTO shortlink.t_link VALUES (1, 'Ab3x', 'https://secret.example/target'),"
                    + " (2, 'Zz9q', 'https://secret.example/other')");
            sql.execute("CREATE USER 'opspilot_ro'@'%' IDENTIFIED BY 'ro-pass'");
            sql.execute("GRANT SELECT ON performance_schema.* TO 'opspilot_ro'@'%'");
            sql.execute("GRANT PROCESS ON *.* TO 'opspilot_ro'@'%'");
            sql.execute("CREATE USER 'opspilot_nops'@'%' IDENTIFIED BY 'nops-pass'");
        }
        try (Connection app = root("shortlink");
                Statement sql = app.createStatement()) {
            for (int i = 0; i < 3; i++) {
                consume(sql.executeQuery("SELECT SLEEP(0.2), target FROM t_link WHERE short_uri = 'Ab3x'"));
            }
            consume(sql.executeQuery("SELECT * FROM t_link WHERE target = 'https://secret.example/target'"));
        }
    }

    @AfterAll
    static void closeApplicationConnections() throws SQLException {
        for (Connection connection : APP) {
            connection.close();
        }
    }

    @Test
    void serverSummaryReadsRealStatus() {
        DatabaseInspectResultV1 result = fetched(InspectionType.SERVER_SUMMARY, null);

        assertThat(result.serverSummary().threadsConnected()).isPositive();
        assertThat(result.serverSummary().threadsRunning()).isPositive();
        assertThat(result.serverSummary().questions()).isPositive();
        assertThat(result.serverSummary().uptimeSeconds()).isPositive();
        assertThat(result.serverSummary().bufferPoolUsage()).isBetween(0.0, 1.0);
    }

    /** 真实连接：3 个空闲、1 个执行 SLEEP 的业务连接；状态分布与最长运行连接只含命令与状态，不含 SQL 原文。 */
    @Test
    void connectionSummaryDescribesRealSessionsOfTheBoundDatabase() throws Exception {
        for (int i = 0; i < 3; i++) {
            APP.add(root("shortlink"));
        }
        Connection busy = root("shortlink");
        APP.add(busy);
        CompletableFuture<Void> sleeping = CompletableFuture.runAsync(() -> {
            try (Statement sql = busy.createStatement()) {
                consume(sql.executeQuery("SELECT SLEEP(3)"));
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });
        Thread.sleep(1_200);

        DatabaseInspectResultV1 result = fetched(InspectionType.CONNECTION_SUMMARY, null);

        assertThat(result.connectionSummary().totalConnections()).isGreaterThanOrEqualTo(4);
        assertThat(result.connectionSummary().runningConnections()).isGreaterThanOrEqualTo(1);
        assertThat(result.connectionSummary().states())
                .extracting(StateCount::state)
                .contains("Sleep", "User sleep");
        assertThat(result.connectionSummary().longest().command()).isEqualTo("Query");
        assertThat(result.connectionSummary().longest().timeSeconds()).isGreaterThanOrEqualTo(1);
        sleeping.join();
    }

    /** 慢语句来自真实 digest：按平均耗时降序、受 limit 限制、只有规范化 SQL（字面量为 ?），没有业务值。 */
    @Test
    void slowQueriesComeFromNormalizedDigestsOfTheBoundDatabase() {
        DatabaseInspectResultV1 all = fetched(InspectionType.SLOW_QUERIES, null);
        DatabaseInspectResultV1 top = fetched(InspectionType.SLOW_QUERIES, 1);
        assertThat(top.slowQueries()).hasSize(1);
        assertThat(top.slowQueries().getFirst().averageLatencyMs())
                .isEqualTo(all.slowQueries().getFirst().averageLatencyMs()); // 按平均耗时降序
        SlowQuery sleep = all.slowQueries().stream()
                .filter(query -> query.normalizedSql().contains("SLEEP")
                        && query.normalizedSql().contains("t_link"))
                .findFirst()
                .orElseThrow();
        assertThat(sleep.normalizedSql()).contains("?").doesNotContain("Ab3x");
        assertThat(sleep.executionCount()).isEqualTo(3);
        assertThat(sleep.averageLatencyMs()).isGreaterThanOrEqualTo(190.0);
        assertThat(sleep.lastSeen()).isNotNull();

        assertThat(all.slowQueries()).hasSizeGreaterThanOrEqualTo(2).hasSizeLessThanOrEqualTo(10);
        assertThat(all.slowQueries())
                .extracting(SlowQuery::normalizedSql)
                .allSatisfy(text -> assertThat(text).doesNotContain("secret.example"));
        // 调查账号自己的 performance_schema 查询不属于该库，不出现
        assertThat(all.slowQueries())
                .extracting(SlowQuery::normalizedSql)
                .noneMatch(text -> text.contains("FROM `performance_schema`"));
    }

    /** 真实行锁等待：A 持有 id=1 的排他锁，B 的 UPDATE 等待；报告等待者、阻塞者与被锁对象。 */
    @Test
    void lockWaitsReportTheRealBlockingRelation() throws Exception {
        Connection holder = root("shortlink");
        Connection waiter = root("shortlink");
        APP.add(holder);
        APP.add(waiter);
        holder.setAutoCommit(false);
        long holderId;
        try (Statement sql = holder.createStatement()) {
            consume(sql.executeQuery("SELECT * FROM t_link WHERE id = 1 FOR UPDATE"));
            ResultSet id = sql.executeQuery("SELECT CONNECTION_ID()");
            id.next();
            holderId = id.getLong(1);
        }
        CompletableFuture<Void> blocked = CompletableFuture.runAsync(() -> {
            try (Statement sql = waiter.createStatement()) {
                // 先执行 3 秒再等锁（B17-R1）：会话已运行时间 ≠ 锁等待时长
                sql.executeUpdate("UPDATE t_link JOIN (SELECT SLEEP(3) AS n) d SET short_uri = 'Qq1w' WHERE id = 1");
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });
        Thread.sleep(4_500); // 约 3 秒执行＋1.5 秒等锁

        DatabaseInspectResultV1 result = fetched(InspectionType.LOCK_WAITS, null);
        holder.rollback();
        blocked.join();

        assertThat(result.lockWaits().waitingCount()).isEqualTo(1);
        // 真实等锁约 1.5 秒（开始时刻精度为秒）；会话运行时间已约 4.5 秒，不能当作等待时长
        assertThat(result.lockWaits().longestWaitSeconds()).isBetween(1L, 2L);
        LockWait wait = result.lockWaits().waits().getFirst();
        assertThat(wait.blockingThreadId()).isEqualTo(holderId);
        assertThat(wait.lockedObject()).isEqualTo("shortlink.t_link");
        assertThat(wait.waitSeconds()).isBetween(1L, 2L);

        DatabaseInspectResultV1 none = fetched(InspectionType.LOCK_WAITS, null);
        assertThat(none.lockWaits().waitingCount()).isZero();
        assertThat(none.lockWaits().longestWaitSeconds()).isNull();
    }

    @Test
    void credentialPrivilegeEndpointAndTimeoutFailuresAreMapped() throws Exception {
        assertThat(fetch(InspectionType.SERVER_SUMMARY, "env://OPSPILOT_MYSQL_WRONG", "opspilot_ro", endpoint()))
                .isEqualTo(
                        new ProviderOutcome.Failed(ErrorCode.AUTHENTICATION_FAILED, "MySQL rejected the credential"));
        // global_status 对所有账号可读；threads 需要 performance_schema 的 SELECT 权限
        assertThat(fetch(InspectionType.CONNECTION_SUMMARY, "env://OPSPILOT_MYSQL_NOPS", "opspilot_nops", endpoint()))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.AUTHORIZATION_DENIED, "MySQL denied the inspection for this account"));
        assertThat(fetch(InspectionType.SERVER_SUMMARY, "env://OPSPILOT_MYSQL_RO", "opspilot_ro", "mysql://db:70000"))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.INVALID_BINDING, "MySQL endpoint must be mysql://host:port without credentials"));
        assertThat(fetch(
                        InspectionType.SERVER_SUMMARY, "env://OPSPILOT_MYSQL_RO", "opspilot_ro", "mysql://u:p@db:3306"))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.INVALID_BINDING, "MySQL endpoint must be mysql://host:port without credentials"));
        // 接受 TCP 却从不发送握手：读超时
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            List<Socket> held = new ArrayList<>();
            Thread.ofVirtual().start(() -> {
                try {
                    held.add(silent.accept());
                } catch (Exception ignored) {
                    // 关闭
                }
            });
            long started = System.nanoTime();
            ProviderOutcome outcome = ProviderInvocations.fetch(
                    provider(),
                    invocation(
                            InspectionType.SERVER_SUMMARY,
                            null,
                            "env://OPSPILOT_MYSQL_RO",
                            "opspilot_ro",
                            "mysql://127.0.0.1:" + silent.getLocalPort(),
                            Duration.ofMillis(500)));
            assertThat(outcome)
                    .isEqualTo(new ProviderOutcome.Failed(
                            ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static DatabaseInspectResultV1 fetched(InspectionType type, Integer limit) {
        ProviderOutcome outcome = ProviderInvocations.fetch(
                provider(),
                invocation(type, limit, "env://OPSPILOT_MYSQL_RO", "opspilot_ro", endpoint(), Duration.ofSeconds(10)));
        assertThat(outcome).as(type.name()).isInstanceOf(ProviderOutcome.Fetched.class);
        ProviderOutcome.Fetched fetched = (ProviderOutcome.Fetched) outcome;
        assertThat(fetched.rawResult()).doesNotContain("secret.example").doesNotContain("Ab3x");
        return (DatabaseInspectResultV1) fetched.result();
    }

    private static ProviderOutcome fetch(InspectionType type, String credentialRef, String username, String endpoint) {
        return ProviderInvocations.fetch(
                provider(), invocation(type, null, credentialRef, username, endpoint, Duration.ofSeconds(10)));
    }

    private static AdmittedInvocation invocation(
            InspectionType type,
            Integer limit,
            String credentialRef,
            String username,
            String endpoint,
            Duration timeout) {
        return ProviderInvocations.admitted(
                CapabilityKey.DATABASE_INSPECT,
                ProviderType.MYSQL,
                endpoint,
                credentialRef,
                "{\"username\":\"" + username + "\"}",
                new MySqlResourceBindingV1("shortlink"),
                new DatabaseInspectArgumentsV1(type, limit),
                null,
                timeout);
    }

    private static MySqlDatabaseInspectProvider provider() {
        return new MySqlDatabaseInspectProvider(
                new ProviderAuthentication(SchemaCodecs.registry(), RESOLVER), Clock.systemUTC());
    }

    private static String endpoint() {
        return "mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306);
    }

    private static Connection root(String database) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database,
                "root",
                MYSQL.getPassword());
    }

    private static void consume(ResultSet rows) throws SQLException {
        try (rows) {
            while (rows.next()) {
                // 只为执行
            }
        }
    }
}
