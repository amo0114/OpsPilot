package io.github.ismoyuan.opspilot.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * 在真实 MySQL 上执行 V005（及其后迁移），验证 action_execution 的执行身份唯一、Approval 归属、状态伴随字段、快照/上下文 Schema
 * 与核对计数上限（04 §45～§47、§82、§98，08 TASK-068）。每个用例在回滚的事务中准备一个已批准的 Action。
 */
@Testcontainers
class ActionExecutionSchemaTest {

    private static final int ER_DUP_ENTRY = 1062;
    private static final int ER_NO_REFERENCED_ROW = 1452;
    private static final int ER_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final String NOW = "'2026-09-30 00:00:00.000'";
    private static final String LATER = "'2026-09-30 00:01:00.000'";
    private static final String RESULT = "'service.restart.result', 1, '{\"accepted\": true}'";

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

    /**
     * @consumer 上的 Incident 与 Diagnosis v1；Plan/Action @action 及其 APPROVED Approval @approval，另一个 Action
     * @other_action 及其 Approval @other_approval；@consumer 的 ACTIVE 策略 @policy（v1）。
     */
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
                + " ('INC-20260930-0001', @system, 'T', 'I', 'EXECUTING', 'MANUAL', 'demo-user', " + NOW + ", "
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
        seedApprovedAction("@action", "@approval");
        seedApprovedAction("@other_action", "@other_approval");
        execute("INSERT INTO recovery_policy (managed_resource_id, policy_key, name, version_no,"
                + " criteria_schema_name, criteria_schema_version, criteria_payload, status, created_at, activated_at)"
                + " VALUES (@consumer, 'consumer-recovery', 'R', 1, 'recovery.policy.criteria', 1, '{\"schemaName\":"
                + " \"recovery.policy.criteria\", \"schemaVersion\": 1, \"criteria\": [{\"criterionKey\":"
                + " \"consumer-running\"}]}', 'ACTIVE', " + NOW + ", " + NOW + ")");
        execute("SET @policy = LAST_INSERT_ID()");
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void migrationV005IsApplied() {
        assertThat(migration.success).isTrue();
        assertThat(migration.migrations)
                .anySatisfy(applied -> assertThat(applied.version).isEqualTo("005"));
    }

    @Test
    void aPendingExecutionStartsWithNoAttemptsAndVersionZero() throws SQLException {
        execute(insert(pending()));

        assertThat(string("SELECT CONCAT(idempotency_key, '/', status, '/', reconciliation_attempt_count, '/',"
                        + " max_reconciliation_attempts, '/', lock_version) FROM action_execution"))
                .isEqualTo(string("SELECT CONCAT('action-execution:', @action, '/PENDING/0/3/0')"));
    }

    /** 同一 Action 只有一个 Execution 身份（04 §46）；幂等键固定由 Action 身份得出，不能换键再建（04 §47）。 */
    @Test
    void oneActionHasOneExecutionIdentity() throws SQLException {
        execute(insert(pending()));

        assertSqlError(() -> execute(insert(pending())), ER_DUP_ENTRY, "uk_action_execution_action");
        Map<String, String> sameKey = pending();
        sameKey.put("remediation_action_id", "@other_action");
        sameKey.put("approval_request_id", "@other_approval");
        assertSqlError(
                () -> execute(insert(sameKey)), ER_CHECK_CONSTRAINT_VIOLATED, "ck_action_execution_idempotency_key");
        Map<String, String> randomKey = pending();
        randomKey.put("idempotency_key", "'action-execution:" + System.nanoTime() + "'");
        assertSqlError(
                () -> execute(insert(randomKey)), ER_CHECK_CONSTRAINT_VIOLATED, "ck_action_execution_idempotency_key");
        Map<String, String> upperCase = pending();
        upperCase.put("remediation_action_id", "@other_action");
        upperCase.put("approval_request_id", "@other_approval");
        upperCase.put("idempotency_key", "CONCAT('ACTION-EXECUTION:', @other_action)");
        assertSqlError(
                () -> execute(insert(upperCase)), ER_CHECK_CONSTRAINT_VIOLATED, "ck_action_execution_idempotency_key");
    }

    @Test
    void theApprovalMustBelongToTheSameAction() {
        Map<String, String> row = pending();
        row.put("approval_request_id", "@other_approval");
        assertSqlError(() -> execute(insert(row)), ER_NO_REFERENCED_ROW, "fk_action_execution_approval");
        Map<String, String> missing = pending();
        missing.put("remediation_action_id", "999999");
        missing.put("idempotency_key", "'action-execution:999999'");
        assertSqlError(() -> execute(insert(missing)), ER_NO_REFERENCED_ROW, "fk_action_execution");
    }

    /** 状态取值检查与伴随字段检查都只接受四个冻结状态；MySQL 报告先求值的那一个。 */
    @ParameterizedTest
    @CsvSource({"UNKNOWN", "pending", "CANCELLED"})
    void statusesAreTheFrozenSet(String status) throws SQLException {
        Map<String, String> row = pending();
        row.put("status", "'" + status + "'");
        assertThatThrownBy(() -> execute(insert(row))).isInstanceOfSatisfying(SQLException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(ER_CHECK_CONSTRAINT_VIOLATED);
            assertThat(ex.getMessage()).containsAnyOf("ck_action_execution_status", "ck_action_execution_outcome");
        });
        assertThat(string("SELECT check_clause FROM information_schema.check_constraints"
                        + " WHERE constraint_schema = DATABASE() AND constraint_name = 'ck_action_execution_status'"))
                .contains("PENDING", "RUNNING", "SUCCEEDED", "FAILED")
                .doesNotContain("UNKNOWN");
    }

    /** 合法的运行中、成功、明确失败与结果不确定终态（04 §79、§82）。 */
    @Test
    void lifecycleRowsWithTheirAccompanyingFieldsAreAccepted() throws SQLException {
        Map<String, String> running = pending();
        running.put("status", "'RUNNING'");
        running.put("started_at", NOW);
        running.put("reconciliation_attempt_count", "2");
        running.put("last_reconciliation_at", LATER);
        running.put("reconciliation_deadline_at", LATER);
        execute(insert(running));
        execute("DELETE FROM action_execution");

        Map<String, String> succeeded = finished("'SUCCEEDED'");
        succeeded.put("result", RESULT);
        execute(insert(succeeded));
        execute("DELETE FROM action_execution");

        Map<String, String> uncertain = finished("'FAILED'");
        uncertain.put("error_code", "'EXECUTION_RESULT_UNCERTAIN'");
        uncertain.put("reconciliation_attempt_count", "3");
        uncertain.put("last_reconciliation_at", LATER);
        uncertain.put("reconciliation_deadline_at", LATER);
        execute(insert(uncertain));
        execute("DELETE FROM action_execution");

        Map<String, String> failedBeforeStart = finished("'FAILED'");
        failedBeforeStart.put("started_at", "NULL");
        failedBeforeStart.put("error_code", "'INVALID_BINDING'");
        execute(insert(failedBeforeStart));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = {
                "PENDING   | started_at                   | NOW",
                "PENDING   | reconciliation_deadline_at   | NOW",
                "PENDING   | error_code                   | 'TIMEOUT'",
                "RUNNING   | started_at                   | NULL",
                "RUNNING   | finished_at                  | NOW",
                "RUNNING   | error_code                   | 'TIMEOUT'",
                "SUCCEEDED | result                       | NULL, NULL, NULL",
                "SUCCEEDED | error_code                   | 'TIMEOUT'",
                "SUCCEEDED | finished_at                  | NULL",
                "FAILED    | error_code                   | NULL",
                "FAILED    | finished_at                  | NULL"
            })
    void accompanyingFieldsFollowTheStatus(String status, String column, String value) {
        Map<String, String> row = status.equals("PENDING") ? pending() : finished("'" + status + "'");
        if (status.equals("RUNNING")) {
            row.put("finished_at", "NULL");
        }
        if (status.equals("SUCCEEDED")) {
            row.put("result", RESULT);
        }
        if (status.equals("FAILED")) {
            row.put("error_code", "'TIMEOUT'");
        }
        row.put(column, value.equals("NOW") ? NOW : value);
        assertSqlError(() -> execute(insert(row)), ER_CHECK_CONSTRAINT_VIOLATED, "ck_action_execution_outcome");
    }

    /** 核对次数不超过已快照上限；已登记尝试必须有尝试时间与冻结的截止时间（04 §82、§98）。 */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "4 | 3 | NOW  | NOW",
                "1 | 0 | NOW  | NOW",
                "1 | 3 | NULL | NOW",
                "1 | 3 | NOW  | NULL",
                "0 | 3 | NOW  | NOW"
            })
    void reconciliationAttemptsStayWithinTheSnapshotLimit(
            String attempts, String max, String lastAttempt, String deadline) {
        Map<String, String> row = pending();
        row.put("status", "'RUNNING'");
        row.put("started_at", NOW);
        row.put("reconciliation_attempt_count", attempts);
        row.put("max_reconciliation_attempts", max);
        row.put("last_reconciliation_at", lastAttempt.equals("NOW") ? NOW : lastAttempt);
        row.put("reconciliation_deadline_at", deadline.equals("NOW") ? NOW : deadline);
        assertSqlError(() -> execute(insert(row)), ER_CHECK_CONSTRAINT_VIOLATED, "ck_action_execution_reconciliation");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = {
                "recovery_policy_snapshot  | '{\"schemaVersion\": 1}'                     | ck_action_execution_policy_snapshot",
                "recovery_policy_snapshot  | '{\"schemaName\": \" \", \"schemaVersion\": 1}' | ck_action_execution_policy_snapshot",
                "recovery_policy_snapshot  | '{\"schemaName\": \"p\", \"schemaVersion\": \"1\"}' | ck_action_execution_policy_snapshot",
                "recovery_policy_snapshot  | '[]'                                         | ck_action_execution_policy_snapshot",
                "recovery_policy_version   | 0                                            | ck_action_execution_policy_snapshot",
                "execution_context_payload | '[]'                                         | ck_action_execution_context",
                "executor_key              | 'Docker Restart'                             | ck_action_execution_executor_key",
                "error_code                | 'timeout'                                    | ck_action_execution_error_code"
            })
    void snapshotsAndPayloadsCarryTheirSchema(String column, String value, String constraint) {
        Map<String, String> row = column.equals("error_code") ? finished("'FAILED'") : pending();
        row.put(column, value);
        assertSqlError(() -> execute(insert(row)), ER_CHECK_CONSTRAINT_VIOLATED, constraint);
    }

    @Test
    void resultColumnsAreSetTogether() {
        Map<String, String> row = finished("'SUCCEEDED'");
        row.put("result", "'service.restart.result', NULL, '{}'");
        assertSqlError(() -> execute(insert(row)), ER_CHECK_CONSTRAINT_VIOLATED, "ck_action_execution_result");
    }

    private void seedApprovedAction(String action, String approval) throws SQLException {
        execute("INSERT INTO remediation_plan (incident_id, diagnosis_id, title, summary, status, created_at,"
                + " updated_at) VALUES (@incident, @diagnosis, '恢复统计消费', '重新启动已停止的统计消费者', 'ACTIVE', "
                + NOW + ", " + NOW + ")");
        execute("INSERT INTO remediation_action (remediation_plan_id, capability_key, target_resource_id,"
                + " parameter_schema_name, parameter_schema_version, parameter_payload, summary,"
                + " expected_impact_summary, risk_level, requires_approval, created_at) VALUES (LAST_INSERT_ID(),"
                + " 'service.restart', @consumer, 'service.restart.request', 1, '{}', '重新启动统计消费者', '消费短暂中断',"
                + " 'MEDIUM', TRUE, " + NOW + ")");
        execute("SET " + action + " = LAST_INSERT_ID()");
        execute("INSERT INTO approval_request (remediation_action_id, status, requested_at, decided_by, decided_at,"
                + " created_at, updated_at) VALUES (" + action + ", 'APPROVED', " + NOW + ", 'demo-user', " + NOW
                + ", " + NOW + ", " + NOW + ")");
        execute("SET " + approval + " = LAST_INSERT_ID()");
    }

    /** 批准事务创建的 PENDING 行（04 §78）；键为列名，值为 SQL 表达式，"result" 代表结果三列。 */
    private static Map<String, String> pending() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("remediation_action_id", "@action");
        row.put("approval_request_id", "@approval");
        row.put("idempotency_key", "CONCAT('action-execution:', @action)");
        row.put("status", "'PENDING'");
        row.put("executor_key", "'docker.service-restart'");
        row.put("recovery_policy_id", "@policy");
        row.put("recovery_policy_version", "1");
        row.put("recovery_policy_snapshot", "'{\"schemaName\": \"recovery.policy.snapshot\", \"schemaVersion\": 1}'");
        row.put("execution_context_schema_name", "'service.restart.execution-context'");
        row.put("execution_context_schema_version", "1");
        row.put("execution_context_payload", "'{\"resourceBindingId\": 1}'");
        row.put("result", "NULL, NULL, NULL");
        row.put("error_code", "NULL");
        row.put("reconciliation_attempt_count", "0");
        row.put("max_reconciliation_attempts", "3");
        row.put("last_reconciliation_at", "NULL");
        row.put("reconciliation_deadline_at", "NULL");
        row.put("started_at", "NULL");
        row.put("finished_at", "NULL");
        row.put("created_at", NOW);
        row.put("updated_at", NOW);
        return row;
    }

    private static Map<String, String> finished(String status) {
        Map<String, String> row = pending();
        row.put("status", status);
        row.put("started_at", NOW);
        row.put("finished_at", LATER);
        return row;
    }

    private static String insert(Map<String, String> row) {
        String columns = String.join(", ", row.keySet())
                .replace("result,", "result_schema_name, result_schema_version, result_payload,");
        return "INSERT INTO action_execution (" + columns + ") VALUES (" + String.join(", ", row.values()) + ")";
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
