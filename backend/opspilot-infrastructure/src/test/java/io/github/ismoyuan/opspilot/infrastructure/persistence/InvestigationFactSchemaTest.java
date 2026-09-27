package io.github.ismoyuan.opspilot.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
 * 在真实 MySQL 上执行 V003，验证调查事实表的上下文互斥、终态一致、唯一关系与同一 Incident/Investigation 的复合外键
 * （04 §17～§36、§59～§61，08 TASK-021）。每个用例在回滚的事务中准备两个 Incident 各自的调查。
 */
@Testcontainers
class InvestigationFactSchemaTest {

    private static final int ER_DUP_ENTRY = 1062;
    private static final int ER_NO_REFERENCED_ROW = 1452;
    private static final int ER_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final String NOW = "'2026-09-27 00:00:00.000'";

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
     * @system/@resource/@other_resource；Incident A（@incident/@investigation）与 Incident B（@incident_b/@investigation_b）；
     * A 的调查调用 @invocation、调查 Observation @observation、假设 @hypothesis；B 的假设 @hypothesis_b；
     * A 的恢复调用 @recovery_invocation 与恢复 Observation @recovery_observation（verification 父表待 TASK-074）。
     */
    @BeforeEach
    void seed() throws SQLException {
        connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        connection.setAutoCommit(false);
        execute("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('shortlink-platform', 'S', 'DEMO', 'ACTIVE', " + NOW + ", " + NOW + ")");
        execute("SET @system = LAST_INSERT_ID()");
        execute(resource("redirect-service"));
        execute("SET @resource = LAST_INSERT_ID()");
        execute(resource("shortlink-redis"));
        execute("SET @other_resource = LAST_INSERT_ID()");
        for (String suffix : new String[] {"", "_b"}) {
            execute("INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status,"
                    + " created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES"
                    + " (CONCAT('INC-20260927-000', " + (suffix.isEmpty() ? 1 : 2) + "), @system, 'T', 'I',"
                    + " 'INVESTIGATING', 'MANUAL', 'demo-user', " + NOW + ", " + NOW + ", " + NOW + ", " + NOW + ")");
            execute("SET @incident" + suffix + " = LAST_INSERT_ID()");
            execute("INSERT INTO investigation (incident_id, started_at, last_activity_at, current_run_no,"
                    + " current_run_started_at, max_capability_calls, max_duration_seconds,"
                    + " agent_step_timeout_seconds, max_consecutive_ai_failures, created_at, updated_at) VALUES"
                    + " (@incident" + suffix + ", " + NOW + ", " + NOW + ", 1, " + NOW + ", 12, 480, 60, 3, " + NOW
                    + ", " + NOW + ")");
            execute("SET @investigation" + suffix + " = LAST_INSERT_ID()");
            execute("INSERT INTO hypothesis (investigation_id, title, status, created_at, updated_at) VALUES"
                    + " (@investigation" + suffix + ", 'Consumer 停止', 'PENDING', " + NOW + ", " + NOW + ")");
            execute("SET @hypothesis" + suffix + " = LAST_INSERT_ID()");
        }
        execute(invocation(
                "@investigation",
                "NULL",
                "1",
                "NULL",
                "NULL",
                "'SUCCEEDED'",
                NOW,
                "0",
                "'queue.inspect.result'",
                "1",
                "'{\"lag\": 2180}'",
                "NULL"));
        execute("SET @invocation = LAST_INSERT_ID()");
        execute(observation("@invocation", "@incident", "@investigation", "NULL", "@resource"));
        execute("SET @observation = LAST_INSERT_ID()");
        execute(invocation(
                "NULL",
                "9001",
                "NULL",
                "'stream-lag-drained'",
                "1",
                "'SUCCEEDED'",
                NOW,
                "0",
                "'queue.inspect.result'",
                "1",
                "'{\"lag\": 3}'",
                "NULL"));
        execute("SET @recovery_invocation = LAST_INSERT_ID()");
        execute(observation("@recovery_invocation", "@incident", "NULL", "9001", "@resource"));
        execute("SET @recovery_observation = LAST_INSERT_ID()");
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void migrationCreatesTheSevenFactTablesAndEvidenceHasNoVersioning() throws SQLException {
        assertThat(migration.success).isTrue();
        assertThat(migration.migrations)
                .anySatisfy(applied -> assertThat(applied.version).isEqualTo("003"));
        assertThat(strings("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"))
                .contains(
                        "capability_invocation",
                        "observation",
                        "hypothesis",
                        "evidence",
                        "diagnosis",
                        "diagnosis_evidence_ref",
                        "agent_step_record");
        // 08 TASK-021：Evidence 无 version_no、无 previous_evidence_id；Observation 无 updated_at（04 §26）
        assertThat(strings("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE()"
                        + " AND table_name = 'evidence' ORDER BY ordinal_position"))
                .containsExactly(
                        "id",
                        "investigation_id",
                        "observation_id",
                        "hypothesis_id",
                        "relation",
                        "reason",
                        "created_at");
        assertThat(strings("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE()"
                        + " AND table_name = 'observation'"))
                .doesNotContain("updated_at", "lock_version");
    }

    @Test
    void acceptsAConsistentInvestigationGraph() throws SQLException {
        execute("INSERT INTO evidence (investigation_id, observation_id, hypothesis_id, relation, reason, created_at)"
                + " VALUES (@investigation, @observation, @hypothesis, 'SUPPORTS', '积压持续增长', " + NOW + ")");
        execute("SET @evidence = LAST_INSERT_ID()");
        execute(diagnosis("@investigation", "1", "'PRIMARY_CAUSE_IDENTIFIED'", "@hypothesis"));
        execute("INSERT INTO diagnosis_evidence_ref (diagnosis_id, evidence_id, created_at) VALUES (LAST_INSERT_ID(),"
                + " @evidence, " + NOW + ")");
        execute(diagnosis("@investigation", "2", "'UNDETERMINED'", "NULL"));
        execute(agentStep("1", "'REQUEST_CAPABILITY'", "'SUCCEEDED'", NOW, "NULL"));
        execute(agentStep("2", "NULL", "'RUNNING'", "NULL", "NULL"));
        execute(agentStep("3", "NULL", "'FAILED'", NOW, "'PROCESS_INTERRUPTED'"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            evidence duplicates observation x hypothesis | 1062 | uk_evidence_observation_hypothesis | INSERT INTO evidence (investigation_id, observation_id, hypothesis_id, relation, reason, created_at) VALUES (@investigation, @observation, @hypothesis, 'SUPPORTS', 'r', NOW(3)), (@investigation, @observation, @hypothesis, 'REFUTES', 'r', NOW(3))
            evidence hypothesis from another investigation | 1452 | fk_evidence_hypothesis | INSERT INTO evidence (investigation_id, observation_id, hypothesis_id, relation, reason, created_at) VALUES (@investigation, @observation, @hypothesis_b, 'SUPPORTS', 'r', NOW(3))
            evidence observation from another investigation | 1452 | fk_evidence_observation | INSERT INTO evidence (investigation_id, observation_id, hypothesis_id, relation, reason, created_at) VALUES (@investigation_b, @observation, @hypothesis_b, 'SUPPORTS', 'r', NOW(3))
            evidence on a recovery observation | 1452 | fk_evidence_observation | INSERT INTO evidence (investigation_id, observation_id, hypothesis_id, relation, reason, created_at) VALUES (@investigation, @recovery_observation, @hypothesis, 'CONTEXT', 'r', NOW(3))
            invocation incident differs from its investigation | 1452 | fk_capability_invocation_investigation | INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, started_at, created_at, updated_at) VALUES (@incident_b, @investigation, 1, 'queue.inspect', @resource, 'RUNNING', 'queue.inspect.request', 1, '{}', NOW(3), NOW(3), NOW(3))
            observation resource differs from its invocation | 1452 | fk_observation_invocation | INSERT INTO observation (incident_id, investigation_id, capability_invocation_id, managed_resource_id, observation_kind, schema_name, schema_version, payload, summary, observed_at, created_at) VALUES (@incident, @investigation, @invocation, @other_resource, 'QUEUE_STATUS', 'queue.inspect.result', 1, '{}', 's', NOW(3), NOW(3))
            observation incident differs from its invocation | 1452 | fk_observation | INSERT INTO observation (incident_id, investigation_id, capability_invocation_id, managed_resource_id, observation_kind, schema_name, schema_version, payload, summary, observed_at, created_at) VALUES (@incident_b, @investigation_b, @invocation, @resource, 'QUEUE_STATUS', 'queue.inspect.result', 1, '{}', 's', NOW(3), NOW(3))
            duplicate recovery sample identity | 1062 | uk_capability_invocation_sample | INSERT INTO capability_invocation (incident_id, recovery_verification_id, criterion_key, sample_index, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, started_at, created_at, updated_at) VALUES (@incident, 9001, 'stream-lag-drained', 1, 'queue.inspect', @resource, 'RUNNING', 'queue.inspect.request', 1, '{}', NOW(3), NOW(3), NOW(3))
            diagnosis version reused | 1062 | uk_diagnosis_investigation_version | INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, summary, impact_summary, created_at) VALUES (@investigation, 1, 1, 'UNDETERMINED', 's', 'i', NOW(3)), (@investigation, 1, 1, 'UNDETERMINED', 's', 'i', NOW(3))
            diagnosis primary hypothesis of another investigation | 1452 | fk_diagnosis_primary_hypothesis | INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, primary_hypothesis_id, summary, impact_summary, created_at) VALUES (@investigation, 1, 1, 'POSSIBLE_CAUSE', @hypothesis_b, 's', 'i', NOW(3))
            agent step number reused across runs | 1062 | uk_agent_step_record_investigation_step | INSERT INTO agent_step_record (incident_id, investigation_id, run_no, step_no, status, started_at, created_at, updated_at) VALUES (@incident, @investigation, 1, 1, 'RUNNING', NOW(3), NOW(3), NOW(3)), (@incident, @investigation, 2, 1, 'RUNNING', NOW(3), NOW(3), NOW(3))
            agent step incident differs from investigation | 1452 | fk_agent_step_record_investigation | INSERT INTO agent_step_record (incident_id, investigation_id, run_no, step_no, status, started_at, created_at, updated_at) VALUES (@incident_b, @investigation, 1, 1, 'RUNNING', NOW(3), NOW(3), NOW(3))
            """)
    void relationalConstraintsRejectCrossContextOrDuplicateRows(
            String scenario, int errorCode, String constraint, String sql) {
        assertSqlError(() -> execute(sql), errorCode, constraint);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            invocation with both contexts | ck_capability_invocation_context | INSERT INTO capability_invocation (incident_id, investigation_id, recovery_verification_id, run_no, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, started_at, created_at, updated_at) VALUES (@incident, @investigation, 9001, 1, 'queue.inspect', @resource, 'RUNNING', 'q', 1, '{}', NOW(3), NOW(3), NOW(3))
            investigation invocation without run | ck_capability_invocation_context | INSERT INTO capability_invocation (incident_id, investigation_id, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, started_at, created_at, updated_at) VALUES (@incident, @investigation, 'queue.inspect', @resource, 'RUNNING', 'q', 1, '{}', NOW(3), NOW(3), NOW(3))
            recovery invocation with run | ck_capability_invocation_context | INSERT INTO capability_invocation (incident_id, recovery_verification_id, run_no, criterion_key, sample_index, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, started_at, created_at, updated_at) VALUES (@incident, 9001, 1, 'c1', 2, 'queue.inspect', @resource, 'RUNNING', 'q', 1, '{}', NOW(3), NOW(3), NOW(3))
            recovery sample index 0 | ck_capability_invocation_context | INSERT INTO capability_invocation (incident_id, recovery_verification_id, criterion_key, sample_index, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, started_at, created_at, updated_at) VALUES (@incident, 9001, 'c1', 0, 'queue.inspect', @resource, 'RUNNING', 'q', 1, '{}', NOW(3), NOW(3), NOW(3))
            succeeded without response | ck_capability_invocation_outcome | INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, started_at, finished_at, duration_ms, created_at, updated_at) VALUES (@incident, @investigation, 1, 'queue.inspect', @resource, 'SUCCEEDED', 'q', 1, '{}', NOW(3), NOW(3), 5, NOW(3), NOW(3))
            failed without error code | ck_capability_invocation_outcome | INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, started_at, finished_at, duration_ms, created_at, updated_at) VALUES (@incident, @investigation, 1, 'queue.inspect', @resource, 'FAILED', 'q', 1, '{}', NOW(3), NOW(3), 5, NOW(3), NOW(3))
            running with finish time | ck_capability_invocation_outcome | INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, started_at, finished_at, created_at, updated_at) VALUES (@incident, @investigation, 1, 'queue.inspect', @resource, 'RUNNING', 'q', 1, '{}', NOW(3), NOW(3), NOW(3), NOW(3))
            response without schema | ck_capability_invocation_response | INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, response_payload, started_at, finished_at, duration_ms, created_at, updated_at) VALUES (@incident, @investigation, 1, 'queue.inspect', @resource, 'SUCCEEDED', 'q', 1, '{}', '{}', NOW(3), NOW(3), 5, NOW(3), NOW(3))
            lowercase invocation status | ck_capability_invocation_ | INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, started_at, created_at, updated_at) VALUES (@incident, @investigation, 1, 'queue.inspect', @resource, 'running', 'q', 1, '{}', NOW(3), NOW(3), NOW(3))
            raw result outside file store | ck_capability_invocation_raw_result_ref | INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key, managed_resource_id, status, request_schema_name, request_schema_version, request_payload, raw_result_ref, started_at, created_at, updated_at) VALUES (@incident, @investigation, 1, 'queue.inspect', @resource, 'RUNNING', 'q', 1, '{}', 'http://evil/raw', NOW(3), NOW(3), NOW(3))
            observation without context | ck_observation_context | INSERT INTO observation (incident_id, capability_invocation_id, managed_resource_id, observation_kind, schema_name, schema_version, payload, summary, observed_at, created_at) VALUES (@incident, @invocation, @resource, 'QUEUE_STATUS', 'q', 1, '{}', 's', NOW(3), NOW(3))
            observation with both contexts | ck_observation_context | INSERT INTO observation (incident_id, investigation_id, recovery_verification_id, capability_invocation_id, managed_resource_id, observation_kind, schema_name, schema_version, payload, summary, observed_at, created_at) VALUES (@incident, @investigation, 9001, @invocation, @resource, 'QUEUE_STATUS', 'q', 1, '{}', 's', NOW(3), NOW(3))
            unknown observation kind | ck_observation_kind | INSERT INTO observation (incident_id, investigation_id, capability_invocation_id, managed_resource_id, observation_kind, schema_name, schema_version, payload, summary, observed_at, created_at) VALUES (@incident, @investigation, @invocation, @resource, 'GUESS', 'q', 1, '{}', 's', NOW(3), NOW(3))
            observation window reversed | ck_observation_window | INSERT INTO observation (incident_id, investigation_id, capability_invocation_id, managed_resource_id, observation_kind, schema_name, schema_version, payload, summary, observed_at, window_start, window_end, created_at) VALUES (@incident, @investigation, @invocation, @resource, 'METRIC', 'q', 1, '{}', 's', NOW(3), '2026-09-27 01:00:00', '2026-09-27 00:00:00', NOW(3))
            unknown evidence relation | ck_evidence_relation | INSERT INTO evidence (investigation_id, observation_id, hypothesis_id, relation, reason, created_at) VALUES (@investigation, @observation, @hypothesis, 'CONFIRMS', 'r', NOW(3))
            primary cause without hypothesis | ck_diagnosis_primary_hypothesis | INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, summary, impact_summary, created_at) VALUES (@investigation, 1, 1, 'PRIMARY_CAUSE_IDENTIFIED', 's', 'i', NOW(3))
            diagnosis run 0 | ck_diagnosis_run_no | INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, summary, impact_summary, created_at) VALUES (@investigation, 0, 1, 'UNDETERMINED', 's', 'i', NOW(3))
            unknown hypothesis status | ck_hypothesis_status | INSERT INTO hypothesis (investigation_id, title, status, created_at, updated_at) VALUES (@investigation, 't', 'CONFIRMED', NOW(3), NOW(3))
            unknown agent intent | ck_agent_step_record_intent | INSERT INTO agent_step_record (incident_id, investigation_id, run_no, step_no, intent_type, status, started_at, finished_at, created_at, updated_at) VALUES (@incident, @investigation, 1, 1, 'EXECUTE_SHELL', 'SUCCEEDED', NOW(3), NOW(3), NOW(3), NOW(3))
            agent step failed without error code | ck_agent_step_record_outcome | INSERT INTO agent_step_record (incident_id, investigation_id, run_no, step_no, status, started_at, finished_at, created_at, updated_at) VALUES (@incident, @investigation, 1, 1, 'FAILED', NOW(3), NOW(3), NOW(3), NOW(3))
            agent output without schema version | ck_agent_step_record_output | INSERT INTO agent_step_record (incident_id, investigation_id, run_no, step_no, status, output_payload, started_at, finished_at, created_at, updated_at) VALUES (@incident, @investigation, 1, 1, 'SUCCEEDED', '{}', NOW(3), NOW(3), NOW(3), NOW(3))
            agent output version without payload | ck_agent_step_record_output | INSERT INTO agent_step_record (incident_id, investigation_id, run_no, step_no, status, output_schema_version, started_at, finished_at, created_at, updated_at) VALUES (@incident, @investigation, 1, 1, 'SUCCEEDED', 1, NOW(3), NOW(3), NOW(3), NOW(3))
            """)
    void checkConstraintsRejectInvalidRows(String scenario, String constraint, String sql) {
        assertSqlError(() -> execute(sql), ER_CHECK_CONSTRAINT_VIOLATED, constraint);
    }

    private static String resource(String key) {
        return "INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status, created_at,"
                + " updated_at) VALUES (@system, '" + key + "', 'R', 'SERVICE', 'ACTIVE', " + NOW + ", " + NOW + ")";
    }

    private static String invocation(
            String investigation,
            String verification,
            String runNo,
            String criterion,
            String sample,
            String status,
            String finishedAt,
            String durationMs,
            String responseSchema,
            String responseVersion,
            String responsePayload,
            String errorCode) {
        return "INSERT INTO capability_invocation (incident_id, investigation_id, recovery_verification_id, run_no,"
                + " criterion_key, sample_index, capability_key, managed_resource_id, status, request_schema_name,"
                + " request_schema_version, request_payload, response_schema_name, response_schema_version,"
                + " response_payload, started_at, finished_at, duration_ms, error_code, created_at, updated_at)"
                + " VALUES (@incident, " + investigation + ", " + verification + ", " + runNo + ", " + criterion + ", "
                + sample + ", 'queue.inspect', @resource, " + status + ", 'queue.inspect.request', 1, '{}', "
                + responseSchema + ", " + responseVersion + ", " + responsePayload + ", " + NOW + ", " + finishedAt
                + ", " + durationMs + ", " + errorCode + ", " + NOW + ", " + NOW + ")";
    }

    private static String observation(
            String invocation, String incident, String investigation, String verification, String resource) {
        return "INSERT INTO observation (incident_id, investigation_id, recovery_verification_id,"
                + " capability_invocation_id, managed_resource_id, observation_kind, schema_name, schema_version,"
                + " payload, summary, observed_at, created_at) VALUES (" + incident + ", " + investigation + ", "
                + verification + ", " + invocation + ", " + resource + ", 'QUEUE_STATUS', 'queue.inspect.result', 1,"
                + " '{\"lag\": 2180}', '消息积压 2180', " + NOW + ", " + NOW + ")";
    }

    private static String diagnosis(String investigation, String version, String conclusion, String hypothesis) {
        return "INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, primary_hypothesis_id,"
                + " summary, impact_summary, created_at) VALUES (" + investigation + ", 1, " + version + ", "
                + conclusion + ", " + hypothesis + ", '统计消费者停止', '统计滞后', " + NOW + ")";
    }

    private static String agentStep(String stepNo, String intent, String status, String finishedAt, String error) {
        return "INSERT INTO agent_step_record (incident_id, investigation_id, run_no, step_no, intent_type, status,"
                + " started_at, finished_at, error_code, created_at, updated_at) VALUES (@incident, @investigation, 1, "
                + stepNo + ", " + intent + ", " + status + ", " + NOW + ", " + finishedAt + ", " + error + ", " + NOW
                + ", " + NOW + ")";
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
