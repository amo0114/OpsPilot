package io.github.ismoyuan.opspilot.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 在真实 MySQL 上执行 V004，验证处理方案三表的外键、一对一关系、状态取值、审批决定伴随字段与 CHANGE 必须审批
 * （04 §38～§44，08 TASK-062）。每个用例在回滚的事务中准备一个已诊断的 Incident。
 */
@Testcontainers
class RemediationSchemaTest {

    private static final int ER_DUP_ENTRY = 1062;
    private static final int ER_NO_REFERENCED_ROW = 1452;
    private static final int ER_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final String NOW = "'2026-09-30 00:00:00.000'";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    private static MigrateResult migration;

    private Connection connection;

    @BeforeAll
    static void migrate() {
        migration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** @system/@consumer；Incident @incident 与其调查的 Diagnosis v1 @diagnosis；ACTIVE Plan @plan 及其 Action @action。 */
    @BeforeEach
    void seed() throws SQLException {
        connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        connection.setAutoCommit(false);
        execute("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('shortlink-platform', 'S', 'DEMO', 'ACTIVE', " + NOW + ", " + NOW + ")");
        execute("SET @system = LAST_INSERT_ID()");
        execute("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) VALUES (@system, 'statistics-consumer', 'C', 'CONSUMER', 'ACTIVE', "
                + NOW + ", " + NOW + ")");
        execute("SET @consumer = LAST_INSERT_ID()");
        execute("INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status,"
                + " created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES"
                + " ('INC-20260930-0001', @system, 'T', 'I', 'DIAGNOSED', 'MANUAL', 'demo-user', " + NOW + ", "
                + NOW + ", " + NOW + ", " + NOW + ")");
        execute("SET @incident = LAST_INSERT_ID()");
        execute("INSERT INTO investigation (incident_id, started_at, last_activity_at, current_run_no,"
                + " current_run_started_at, max_capability_calls, max_duration_seconds, agent_step_timeout_seconds,"
                + " max_consecutive_ai_failures, created_at, updated_at) VALUES (@incident, " + NOW + ", " + NOW
                + ", 1, " + NOW + ", 12, 480, 60, 3, " + NOW + ", " + NOW + ")");
        execute("SET @investigation = LAST_INSERT_ID()");
        execute("INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, summary,"
                + " impact_summary, created_at) VALUES (@investigation, 1, 1, 'UNDETERMINED', 'S', 'I', " + NOW + ")");
        execute("SET @diagnosis = LAST_INSERT_ID()");
        execute(plan("'ACTIVE'"));
        execute("SET @plan = LAST_INSERT_ID()");
        execute(action("@plan", "'service.restart'", "'MEDIUM'", "TRUE", "'{}'"));
        execute("SET @action = LAST_INSERT_ID()");
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void migrationV004IsApplied() {
        assertThat(migration.success).isTrue();
        assertThat(migration.migrations)
                .anySatisfy(applied -> assertThat(applied.version).isEqualTo("004"));
    }

    /** V0.1 一个 Plan 一个 Action、一个 Action 最多一条 Approval（04 §41、§43）。 */
    @Test
    void onePlanHasOneActionAndOneActionHasOneApproval() throws SQLException {
        execute(approval("'PENDING'", "NULL", "NULL", "NULL"));

        assertSqlError(
                () -> execute(action("@plan", "'service.restart'", "'MEDIUM'", "TRUE", "'{}'")),
                ER_DUP_ENTRY,
                "uk_remediation_action_plan");
        assertSqlError(
                () -> execute(approval("'PENDING'", "NULL", "NULL", "NULL")),
                ER_DUP_ENTRY,
                "uk_approval_request_action");
    }

    @Test
    void referencesMustExist() throws SQLException {
        assertSqlError(
                () -> execute("INSERT INTO remediation_plan (incident_id, diagnosis_id, title, summary, status,"
                        + " created_at, updated_at) VALUES (@incident, 999999, 'T', 'S', 'ACTIVE', " + NOW + ", "
                        + NOW + ")"),
                ER_NO_REFERENCED_ROW,
                "fk_remediation_plan_diagnosis");
        execute(plan("'ACTIVE'"));
        execute("SET @another_plan = LAST_INSERT_ID()");
        assertSqlError(
                () -> execute("INSERT INTO remediation_action (remediation_plan_id, capability_key,"
                        + " target_resource_id, parameter_schema_name, parameter_schema_version, parameter_payload,"
                        + " summary, expected_impact_summary, risk_level, requires_approval, created_at) VALUES"
                        + " (@another_plan, 'service.restart', 999999, 'service.restart.request', 1, '{}', 'S', 'E',"
                        + " 'MEDIUM', TRUE, " + NOW + ")"),
                ER_NO_REFERENCED_ROW,
                "fk_remediation_action_target");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "'service.restart' | 'HIGH'   | TRUE  | '{}'    | ck_remediation_action_risk_level",
                "'service.restart' | 'MEDIUM' | FALSE | '{}'    | ck_remediation_action_requires_approval",
                "'docker restart'  | 'MEDIUM' | TRUE  | '{}'    | ck_remediation_action_capability_key",
                "'service.restart' | 'MEDIUM' | TRUE  | '[]'    | ck_remediation_action_parameters",
                "'service.restart' | 'medium' | TRUE  | '{}'    | ck_remediation_action_risk_level"
            })
    void actionValuesAreConstrained(String key, String risk, String approval, String payload, String constraint)
            throws SQLException {
        execute(plan("'ACTIVE'"));
        assertSqlError(
                () -> execute(action("LAST_INSERT_ID()", key, risk, approval, payload)),
                ER_CHECK_CONSTRAINT_VIOLATED,
                constraint);
    }

    @Test
    void planStatusesAreTheFrozenSet() {
        assertSqlError(() -> execute(plan("'APPROVED'")), ER_CHECK_CONSTRAINT_VIOLATED, "ck_remediation_plan_status");
        assertSqlError(() -> execute(plan("'active'")), ER_CHECK_CONSTRAINT_VIOLATED, "ck_remediation_plan_status");
    }

    /** PENDING 没有决定字段；其余状态必须有决定人与时间，comment 只随决定出现（04 §43、01 §25）。 */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "'PENDING'   | 'demo-user' | NULL | NULL",
                "'PENDING'   | NULL        | NULL | '批准'",
                "'APPROVED'  | NULL        | NOW  | NULL",
                "'REJECTED'  | 'demo-user' | NULL | NULL",
                "'CANCELLED' | '   '       | NOW  | NULL",
                "'EXPIRED'   | 'demo-user' | NOW  | NULL"
            })
    void approvalDecisionFieldsFollowTheStatus(String status, String decidedBy, String decidedAt, String comment) {
        String at = decidedAt.equals("NOW") ? NOW : decidedAt;
        assertSqlError(
                () -> execute(approval(status, decidedBy, at, comment)),
                ER_CHECK_CONSTRAINT_VIOLATED,
                status.equals("'EXPIRED'") ? "ck_approval_request_status" : "ck_approval_request_decision");
    }

    @Test
    void decidedApprovalsKeepTheDecisionRecord() throws SQLException {
        execute(approval("'REJECTED'", "'demo-user'", NOW, "'当前不希望重启消费者。'"));

        assertThat(string("SELECT CONCAT(status, '/', decided_by, '/', comment, '/', lock_version)"
                        + " FROM approval_request"))
                .isEqualTo("REJECTED/demo-user/当前不希望重启消费者。/0");
    }

    private static String plan(String status) {
        return "INSERT INTO remediation_plan (incident_id, diagnosis_id, title, summary, status, created_at,"
                + " updated_at) VALUES (@incident, @diagnosis, '恢复统计消费', '重新启动已停止的统计消费者', " + status
                + ", " + NOW + ", " + NOW + ")";
    }

    private static String action(String plan, String key, String risk, String requiresApproval, String payload) {
        return "INSERT INTO remediation_action (remediation_plan_id, capability_key, target_resource_id,"
                + " parameter_schema_name, parameter_schema_version, parameter_payload, summary,"
                + " expected_impact_summary, risk_level, requires_approval, created_at) VALUES (" + plan + ", " + key
                + ", @consumer, 'service.restart.request', 1, " + payload + ", '重新启动统计消费者', '消费短暂中断', " + risk
                + ", " + requiresApproval + ", " + NOW + ")";
    }

    private static String approval(String status, String decidedBy, String decidedAt, String comment) {
        return "INSERT INTO approval_request (remediation_action_id, status, requested_at, decided_by, decided_at,"
                + " comment, created_at, updated_at) VALUES (@action, " + status + ", " + NOW + ", " + decidedBy + ", "
                + decidedAt + ", " + comment + ", " + NOW + ", " + NOW + ")";
    }

    private String string(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void assertSqlError(SqlAction action, int errorCode, String constraint) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(errorCode);
            assertThat(ex.getMessage()).contains(constraint);
        });
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
