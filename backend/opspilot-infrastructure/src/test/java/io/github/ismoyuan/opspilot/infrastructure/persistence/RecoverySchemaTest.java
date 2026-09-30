package io.github.ismoyuan.opspilot.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 在真实 MySQL 上执行 V006，验证 recovery_policy、recovery_verification 的版本/编号/执行唯一性、状态伴随字段与 Schema，
 * 以及 Execution→Policy、Invocation/Observation→Verification 的最终外键（04 §18～§23、§48～§54、§80、§97，
 * 08 TASK-074）。每个用例在回滚的事务中准备数据。
 */
@Testcontainers
class RecoverySchemaTest {

    private static final int ER_DUP_ENTRY = 1062;
    private static final int ER_ROW_IS_REFERENCED = 1451;
    private static final int ER_NO_REFERENCED_ROW = 1452;
    private static final int ER_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final String NOW = "'2026-09-30 00:00:00.000'";
    private static final String LATER = "'2026-09-30 00:02:00.000'";
    private static final String CRITERIA = "'{\"schemaName\": \"recovery.policy.criteria\", \"schemaVersion\": 1,"
            + " \"criteria\": [{\"criterionKey\": \"consumer-running\"}]}'";
    private static final String SNAPSHOT = "'{\"schemaName\": \"recovery.policy.criteria\", \"schemaVersion\": 1}'";
    private static final String RESULT =
            "'{\"schemaName\": \"recovery.verification.result\", \"schemaVersion\": 1, \"overallResult\": \"PASSED\"}'";

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
     * @consumer 与 @stream；Incident A（@incident/@investigation，已批准的 Action @action 与 Approval @approval）与
     * Incident B（@incident_b）；@consumer 的 ACTIVE 策略 @policy（v1）。
     */
    @BeforeEach
    void seed() throws SQLException {
        connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        connection.setAutoCommit(false);
        execute("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('shortlink-platform', 'S', 'DEMO', 'ACTIVE', " + NOW + ", " + NOW + ")");
        execute("SET @system = LAST_INSERT_ID()");
        execute(resource("statistics-consumer", "CONSUMER"));
        execute("SET @consumer = LAST_INSERT_ID()");
        execute(resource("statistics-stream", "MESSAGE_QUEUE"));
        execute("SET @stream = LAST_INSERT_ID()");
        for (String suffix : new String[] {"", "_b"}) {
            execute("INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status,"
                    + " created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES"
                    + " (CONCAT('INC-20260930-000', " + (suffix.isEmpty() ? 1 : 2) + "), @system, 'T', 'I',"
                    + " 'EXECUTING', 'MANUAL', 'demo-user', " + NOW + ", " + NOW + ", " + NOW + ", " + NOW + ")");
            execute("SET @incident" + suffix + " = LAST_INSERT_ID()");
        }
        execute("INSERT INTO investigation (incident_id, started_at, last_activity_at, current_run_no,"
                + " current_run_started_at, max_capability_calls, max_duration_seconds, agent_step_timeout_seconds,"
                + " max_consecutive_ai_failures, created_at, updated_at) VALUES (@incident, " + NOW + ", " + NOW
                + ", 1, " + NOW + ", 12, 480, 60, 3, " + NOW + ", " + NOW + ")");
        execute("SET @investigation = LAST_INSERT_ID()");
        execute("INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, summary,"
                + " impact_summary, created_at) VALUES (@investigation, 1, 1, 'UNDETERMINED', 'S', 'I', " + NOW + ")");
        execute("INSERT INTO remediation_plan (incident_id, diagnosis_id, title, summary, status, created_at,"
                + " updated_at) VALUES (@incident, LAST_INSERT_ID(), 'T', 'S', 'ACTIVE', " + NOW + ", " + NOW + ")");
        execute("INSERT INTO remediation_action (remediation_plan_id, capability_key, target_resource_id,"
                + " parameter_schema_name, parameter_schema_version, parameter_payload, summary,"
                + " expected_impact_summary, risk_level, requires_approval, created_at) VALUES (LAST_INSERT_ID(),"
                + " 'service.restart', @consumer, 'service.restart.request', 1, '{}', 'S', 'E', 'MEDIUM', TRUE, "
                + NOW + ")");
        execute("SET @action = LAST_INSERT_ID()");
        execute("INSERT INTO approval_request (remediation_action_id, status, requested_at, decided_by, decided_at,"
                + " created_at, updated_at) VALUES (@action, 'APPROVED', " + NOW + ", 'demo-user', " + NOW + ", "
                + NOW + ", " + NOW + ")");
        execute("SET @approval = LAST_INSERT_ID()");
        execute(policy("@consumer", "'consumer-recovery'", "1", "'ACTIVE'", "NULL", CRITERIA));
        execute("SET @policy = LAST_INSERT_ID()");
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void migrationV006IsApplied() throws SQLException {
        assertThat(migration.success).isTrue();
        assertThat(migration.migrations)
                .anySatisfy(applied -> assertThat(applied.version).isEqualTo("006"));
        assertThat(strings("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"))
                .contains("action_execution", "recovery_policy", "recovery_verification");
    }

    /** 终版不遗留无约束恢复引用（07 §92、08 TASK-021/074）：每个恢复引用列都参与外键。 */
    @Test
    void everyRecoveryReferenceIsConstrained() throws SQLException {
        assertThat(strings("SELECT CONCAT(c.table_name, '.', c.column_name) FROM information_schema.columns c"
                        + " WHERE c.table_schema = DATABASE() AND c.column_name IN ('recovery_verification_id',"
                        + " 'recovery_policy_id', 'action_execution_id') AND NOT EXISTS (SELECT 1 FROM"
                        + " information_schema.key_column_usage k WHERE k.table_schema = c.table_schema AND"
                        + " k.table_name = c.table_name AND k.column_name = c.column_name AND"
                        + " k.referenced_table_name IS NOT NULL)"))
                .isEmpty();
        assertThat(strings("SELECT CONCAT(constraint_name, '>', referenced_table_name) FROM"
                        + " information_schema.referential_constraints WHERE constraint_schema = DATABASE() AND"
                        + " referenced_table_name IN ('recovery_policy', 'recovery_verification')"))
                .containsExactlyInAnyOrder(
                        "fk_action_execution_policy>recovery_policy",
                        "fk_recovery_verification_policy>recovery_policy",
                        "fk_capability_invocation_verification>recovery_verification",
                        "fk_observation_verification>recovery_verification");
    }

    // ---------------------------------------------------------------- recovery_policy

    /** 版本唯一；新版本或另一个 policy_key 可以插入（04 §48）。 */
    @Test
    void policyVersionsAreUniquePerResourceAndKey() throws SQLException {
        assertSqlError(
                () -> execute(policy("@consumer", "'consumer-recovery'", "1", "'RETIRED'", LATER, CRITERIA)),
                ER_DUP_ENTRY,
                "uk_recovery_policy_version");
        execute(policy("@consumer", "'consumer-recovery'", "2", "'RETIRED'", LATER, CRITERIA));
        execute(policy("@consumer", "'consumer-restart'", "1", "'RETIRED'", LATER, CRITERIA));
        execute(policy("@stream", "'consumer-recovery'", "1", "'ACTIVE'", "NULL", CRITERIA));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = {
                "criteria empty       | '{\"schemaName\": \"recovery.policy.criteria\", \"schemaVersion\": 1, \"criteria\": []}'",
                "criteria missing     | '{\"schemaName\": \"recovery.policy.criteria\", \"schemaVersion\": 1}'",
                "criteria not array   | '{\"schemaName\": \"recovery.policy.criteria\", \"schemaVersion\": 1, \"criteria\": {}}'",
                "schema name differs  | '{\"schemaName\": \"recovery.policy\", \"schemaVersion\": 1, \"criteria\": [{}]}'",
                "schema name case     | '{\"schemaName\": \"Recovery.Policy.Criteria\", \"schemaVersion\": 1, \"criteria\": [{}]}'",
                "schema version differs | '{\"schemaName\": \"recovery.policy.criteria\", \"schemaVersion\": 2, \"criteria\": [{}]}'",
                "schema version string  | '{\"schemaName\": \"recovery.policy.criteria\", \"schemaVersion\": \"1\", \"criteria\": [{}]}'",
                "payload array        | '[]'"
            })
    void criteriaCarryTheirSchemaAndAreNotEmpty(String description, String payload) {
        assertSqlError(
                () -> execute(policy("@stream", "'stream-recovery'", "1", "'ACTIVE'", "NULL", payload)),
                ER_CHECK_CONSTRAINT_VIOLATED,
                "ck_recovery_policy_criteria");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = {
                "'Stream Recovery' | 1 | 'ACTIVE'  | NULL  | ck_recovery_policy_key",
                "'stream-recovery' | 0 | 'ACTIVE'  | NULL  | ck_recovery_policy_version",
                "'stream-recovery' | 1 | 'ACTIVE'  | LATER | ck_recovery_policy_retirement",
                "'stream-recovery' | 1 | 'RETIRED' | NULL  | ck_recovery_policy_retirement",
                "'stream-recovery' | 1 | 'RETIRED' | EARLY | ck_recovery_policy_retirement",
                "'stream-recovery' | 1 | 'retired' | LATER | ck_recovery_policy_"
            })
    void policyValuesAreConstrained(String key, String version, String status, String retiredAt, String constraint) {
        String retired = switch (retiredAt) {
            case "LATER" -> LATER;
            case "EARLY" -> "'2026-09-29 23:59:59.999'";
            default -> retiredAt;
        };
        assertSqlError(
                () -> execute(policy("@stream", key, version, status, retired, CRITERIA)),
                ER_CHECK_CONSTRAINT_VIOLATED,
                constraint);
    }

    // ---------------------------------------------------------------- action_execution → recovery_policy

    /** Execution 冻结的策略 id 与版本必须是同一策略行（04 §45、§52）。 */
    @Test
    void executionsReferenceAnExistingPolicyVersion() throws SQLException {
        assertSqlError(() -> execute(execution("@policy", "2")), ER_NO_REFERENCED_ROW, "fk_action_execution_policy");
        assertSqlError(() -> execute(execution("999999", "1")), ER_NO_REFERENCED_ROW, "fk_action_execution_policy");

        execute(execution("@policy", "1"));
        assertSqlError(() -> execute("DELETE FROM recovery_policy WHERE id = @policy"), ER_ROW_IS_REFERENCED, "fk_");
    }

    // ---------------------------------------------------------------- recovery_verification

    /** 外部处理的 Verification 没有 Execution，可有多次；编号按 Incident 唯一（04 §53、§54）。 */
    @Test
    void externalVerificationsAreNumberedPerIncident() throws SQLException {
        execute(insert(verification("@incident", "NULL", "1", "INCONCLUSIVE")));
        execute(insert(verification("@incident", "NULL", "2", "PENDING")));
        execute(insert(verification("@incident_b", "NULL", "1", "PENDING")));

        assertSqlError(
                () -> execute(insert(verification("@incident", "NULL", "2", "PENDING"))),
                ER_DUP_ENTRY,
                "uk_recovery_verification_incident_no");
    }

    /** 同一 Execution 最多一个 Verification（DB-INV-003）。 */
    @Test
    void oneExecutionHasAtMostOneVerification() throws SQLException {
        execute(execution("@policy", "1"));
        execute("SET @execution = LAST_INSERT_ID()");
        execute(insert(verification("@incident", "@execution", "1", "PENDING")));

        assertSqlError(
                () -> execute(insert(verification("@incident", "@execution", "2", "PENDING"))),
                ER_DUP_ENTRY,
                "uk_recovery_verification_execution");
        assertSqlError(
                () -> execute(insert(verification("@incident", "999999", "3", "PENDING"))),
                ER_NO_REFERENCED_ROW,
                "fk_recovery_verification_execution");
    }

    /** 策略版本与所验证资源必须与引用的策略行一致（04 §51）。 */
    @Test
    void theVerifiedResourceAndVersionBelongToThePolicy() {
        Map<String, String> otherResource = verification("@incident", "NULL", "1", "PENDING");
        otherResource.put("managed_resource_id", "@stream");
        assertSqlError(() -> execute(insert(otherResource)), ER_NO_REFERENCED_ROW, "fk_recovery_verification_policy");
        Map<String, String> otherVersion = verification("@incident", "NULL", "1", "PENDING");
        otherVersion.put("recovery_policy_version", "2");
        assertSqlError(() -> execute(insert(otherVersion)), ER_NO_REFERENCED_ROW, "fk_recovery_verification_policy");
    }

    /** 状态取值检查与伴随字段检查都只接受五个冻结状态；MySQL 报告先求值的那一个。 */
    @ParameterizedTest
    @CsvSource({"SUCCEEDED", "passed", "UNKNOWN"})
    void verificationStatusesAreTheFrozenSet(String status) throws SQLException {
        assertThatThrownBy(() -> execute(insert(verification("@incident", "NULL", "1", status))))
                .isInstanceOfSatisfying(SQLException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ER_CHECK_CONSTRAINT_VIOLATED);
                    assertThat(ex.getMessage())
                            .containsAnyOf("ck_recovery_verification_status", "ck_recovery_verification_outcome");
                });
        assertThat(strings("SELECT check_clause FROM information_schema.check_constraints WHERE constraint_schema ="
                        + " DATABASE() AND constraint_name = 'ck_recovery_verification_status'"))
                .singleElement()
                .asString()
                .contains("PENDING", "RUNNING", "PASSED", "FAILED", "INCONCLUSIVE")
                .doesNotContain("SUCCEEDED");
    }

    /** 运行中结果可以为空或已部分重建；终态可以没有 started_at（重启后已过期限直接收束，04 §80）。 */
    @Test
    void lifecycleRowsWithTheirAccompanyingFieldsAreAccepted() throws SQLException {
        execute(insert(verification("@incident", "NULL", "1", "RUNNING")));
        Map<String, String> partial = verification("@incident", "NULL", "2", "RUNNING");
        partial.put("result_payload", RESULT);
        execute(insert(partial));
        Map<String, String> neverStarted = verification("@incident", "NULL", "3", "INCONCLUSIVE");
        neverStarted.put("started_at", "NULL");
        execute(insert(neverStarted));
        execute(insert(verification("@incident", "NULL", "4", "FAILED")));
        execute(insert(verification("@incident", "NULL", "5", "PASSED")));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = {
                "PENDING      | started_at     | NOW",
                "PENDING      | result_payload | RESULT",
                "PENDING      | result_summary | '恢复'",
                "RUNNING      | started_at     | NULL",
                "RUNNING      | finished_at    | NOW",
                "RUNNING      | result_summary | '恢复'",
                "PASSED       | result_payload | NULL",
                "FAILED       | result_summary | NULL",
                "INCONCLUSIVE | finished_at    | NULL"
            })
    void accompanyingFieldsFollowTheStatus(String status, String column, String value) {
        Map<String, String> row = verification("@incident", "NULL", "1", status);
        row.put(
                column,
                switch (value) {
                    case "NOW" -> NOW;
                    case "RESULT" -> RESULT;
                    default -> value;
                });
        assertSqlError(() -> execute(insert(row)), ER_CHECK_CONSTRAINT_VIOLATED, "ck_recovery_verification_outcome");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = {
                "policy_snapshot | '{\"schemaVersion\": 1}'                            | ck_recovery_verification_policy_snapshot",
                "policy_snapshot | '{\"schemaName\": \"p\", \"schemaVersion\": 0}'     | ck_recovery_verification_policy_snapshot",
                "policy_snapshot | '[]'                                                | ck_recovery_verification_policy_snapshot",
                "result_payload  | '{\"overallResult\": \"PASSED\"}'                   | ck_recovery_verification_result_payload",
                "result_payload  | '{\"schemaName\": \"r\", \"schemaVersion\": \"1\"}' | ck_recovery_verification_result_payload",
                "result_summary  | '   '                                               | ck_recovery_verification_result_summary",
                "deadline_at     | '2026-09-29 23:59:59.999'                           | ck_recovery_verification_deadline",
                "verification_no | 0                                                   | ck_recovery_verification_no"
            })
    void payloadsCarryTheirSchemaAndValuesAreConstrained(String column, String value, String constraint) {
        Map<String, String> row = verification("@incident", "NULL", "1", "PASSED");
        row.put(column, value);
        assertSqlError(() -> execute(insert(row)), ER_CHECK_CONSTRAINT_VIOLATED, constraint);
    }

    // ---------------------------------------------------------------- 恢复样本与恢复观测

    /** 恢复调用/观测引用真实 Verification，且 incident_id 与之一致（04 §19～§20、§23）；调查上下文不受影响。 */
    @Test
    void recoverySamplesAndObservationsReferenceAVerificationOfTheSameIncident() throws SQLException {
        execute(insert(verification("@incident", "NULL", "1", "RUNNING")));
        execute("SET @verification = LAST_INSERT_ID()");
        execute(insert(verification("@incident_b", "NULL", "1", "RUNNING")));
        execute("SET @verification_b = LAST_INSERT_ID()");

        assertSqlError(
                () -> execute(recoveryInvocation("@incident", "999999", 1)),
                ER_NO_REFERENCED_ROW,
                "fk_capability_invocation_verification");
        assertSqlError(
                () -> execute(recoveryInvocation("@incident", "@verification_b", 1)),
                ER_NO_REFERENCED_ROW,
                "fk_capability_invocation_verification");

        execute(recoveryInvocation("@incident", "@verification", 1));
        execute("SET @sample = LAST_INSERT_ID()");
        assertSqlError(
                () -> execute(recoveryObservation("@verification_b")),
                ER_NO_REFERENCED_ROW,
                "fk_observation_verification");
        execute(recoveryObservation("@verification"));
        execute("INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key,"
                + " managed_resource_id, status, request_schema_name, request_schema_version, request_payload,"
                + " started_at, created_at, updated_at) VALUES (@incident, @investigation, 1, 'queue.inspect',"
                + " @stream, 'RUNNING', 'queue.inspect.request', 1, '{}', " + NOW + ", " + NOW + ", " + NOW + ")");

        assertSqlError(
                () -> execute(recoveryInvocation("@incident", "@verification", 1)),
                ER_DUP_ENTRY,
                "uk_capability_invocation_sample");
        assertSqlError(
                () -> execute("DELETE FROM recovery_verification WHERE id = @verification"),
                ER_ROW_IS_REFERENCED,
                "fk_");
    }

    private static String resource(String key, String type) {
        return "INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) VALUES (@system, '" + key + "', 'R', '" + type + "', 'ACTIVE', " + NOW
                + ", " + NOW + ")";
    }

    private static String policy(
            String resource, String key, String version, String status, String retiredAt, String criteria) {
        return "INSERT INTO recovery_policy (managed_resource_id, policy_key, name, version_no, criteria_schema_name,"
                + " criteria_schema_version, criteria_payload, status, created_at, activated_at, retired_at) VALUES ("
                + resource + ", " + key + ", '消费者恢复', " + version + ", 'recovery.policy.criteria', 1, " + criteria
                + ", " + status + ", " + NOW + ", " + NOW + ", " + retiredAt + ")";
    }

    private static String execution(String policy, String version) {
        return "INSERT INTO action_execution (remediation_action_id, approval_request_id, idempotency_key, status,"
                + " executor_key, recovery_policy_id, recovery_policy_version, recovery_policy_snapshot,"
                + " execution_context_schema_name, execution_context_schema_version, execution_context_payload,"
                + " max_reconciliation_attempts, created_at, updated_at) VALUES (@action, @approval,"
                + " CONCAT('action-execution:', @action), 'PENDING', 'docker.service-restart', " + policy + ", "
                + version + ", " + SNAPSHOT + ", 'service.restart.execution-context', 1, '{}', 3, " + NOW + ", " + NOW
                + ")";
    }

    /**
     * @consumer 上依据 @policy v1 的 Verification；键为列名，值为 SQL 表达式。RUNNING 已开始；终态已开始、已结束并有结果与
     * 摘要；PENDING 均为空。
     */
    private static Map<String, String> verification(String incident, String execution, String number, String status) {
        boolean terminal = !status.equals("PENDING") && !status.equals("RUNNING");
        Map<String, String> row = new LinkedHashMap<>();
        row.put("incident_id", incident);
        row.put("action_execution_id", execution);
        row.put("managed_resource_id", "@consumer");
        row.put("recovery_policy_id", "@policy");
        row.put("recovery_policy_version", "1");
        row.put("policy_snapshot", SNAPSHOT);
        row.put("verification_no", number);
        row.put("status", "'" + status + "'");
        row.put("result_summary", terminal ? "'服务已恢复'" : "NULL");
        row.put("result_payload", terminal ? RESULT : "NULL");
        row.put("deadline_at", LATER);
        row.put("started_at", status.equals("PENDING") ? "NULL" : NOW);
        row.put("finished_at", terminal ? LATER : "NULL");
        row.put("created_at", NOW);
        row.put("updated_at", NOW);
        return row;
    }

    private static String insert(Map<String, String> verification) {
        return "INSERT INTO recovery_verification (" + String.join(", ", verification.keySet()) + ") VALUES ("
                + String.join(", ", verification.values()) + ")";
    }

    private static String recoveryInvocation(String incident, String verification, int sampleIndex) {
        return "INSERT INTO capability_invocation (incident_id, recovery_verification_id, criterion_key, sample_index,"
                + " capability_key, managed_resource_id, status, request_schema_name, request_schema_version,"
                + " request_payload, started_at, created_at, updated_at) VALUES (" + incident + ", " + verification
                + ", 'consumer-running', " + sampleIndex + ", 'service.inspect', @consumer, 'RUNNING',"
                + " 'service.inspect.request', 1, '{}', " + NOW + ", " + NOW + ", " + NOW + ")";
    }

    private static String recoveryObservation(String verification) {
        return "INSERT INTO observation (incident_id, recovery_verification_id, capability_invocation_id,"
                + " managed_resource_id, observation_kind, schema_name, schema_version, payload, summary, observed_at,"
                + " created_at) VALUES (@incident, " + verification + ", @sample, @consumer, 'SERVICE_STATUS',"
                + " 'service.inspect.result', 1, '{\"runtimeState\": \"RUNNING\"}', '消费者运行中', " + NOW + ", " + NOW
                + ")";
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
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
