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

/** 在真实 MySQL 上执行 V002，验证 04 §13～§16、§55～§58、§98 的 FK、UNIQUE、索引与 CHECK（不用 H2）。 */
@Testcontainers
class IncidentInvestigationSchemaTest {

    private static final int ER_DUP_ENTRY = 1062;
    private static final int ER_ROW_IS_REFERENCED = 1451;
    private static final int ER_NO_REFERENCED_ROW = 1452;
    private static final int ER_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final String NOW = "'2026-09-26 00:00:00.000'";
    private static final String PAYLOAD = "'{\"schemaName\": \"timeline.incident-created\", \"schemaVersion\": 1}'";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    private static MigrateResult migration;

    private Connection connection;
    private long systemId;
    private long resourceId;

    @BeforeAll
    static void migrate() {
        migration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        connection.setAutoCommit(false);
        systemId = insert("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('shortlink-platform', 'ShortLink', 'DEMO', 'ACTIVE', " + NOW + ", " + NOW + ")");
        resourceId = insert("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type,"
                + " status, created_at, updated_at) VALUES (" + systemId + ", 'redirect-service', 'Redirect',"
                + " 'SERVICE', 'ACTIVE', " + NOW + ", " + NOW + ")");
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void migrationCreatesIncidentCoreTablesAndIndexes() throws SQLException {
        assertThat(migration.success).isTrue();
        assertThat(migration.targetSchemaVersion).isEqualTo("002");
        assertThat(strings("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"))
                .contains("incident", "incident_affected_resource", "investigation", "incident_timeline_event");
        assertThat(strings("SELECT CONCAT(table_name, '.', index_name, '(', GROUP_CONCAT(column_name ORDER BY"
                        + " seq_in_index), ')') FROM information_schema.statistics WHERE table_schema = DATABASE()"
                        + " AND table_name IN ('incident', 'incident_timeline_event', 'investigation',"
                        + " 'incident_affected_resource') GROUP BY table_name, index_name"))
                .contains(
                        "incident.uk_incident_key(incident_key)",
                        "incident.idx_incident_system_status_created(managed_system_id,status,created_at)",
                        "incident.idx_incident_status_created(status,created_at)",
                        "incident.idx_incident_detected(detected_at)",
                        "incident_affected_resource.PRIMARY(incident_id,managed_resource_id)",
                        "investigation.uk_investigation_incident(incident_id)",
                        "incident_timeline_event.idx_incident_timeline_event_incident(incident_id,id)");
    }

    @Test
    void acceptsAValidIncidentWorkspace() throws SQLException {
        long incidentId = insertIncident("INC-20260926-0001", "'INVESTIGATING'", "NULL");
        insertAffected(incidentId, resourceId);
        long investigationId = insertInvestigation(incidentId);
        execute("UPDATE investigation SET current_run_no = 2, current_run_capability_count = 12,"
                + " capability_call_count = 20, stop_requested_at = " + NOW + ", stop_requested_by = 'demo-user'"
                + " WHERE id = " + investigationId);
        insertTimeline(incidentId, "'INVESTIGATION_STARTED'", "'USER'", "'demo-user'", PAYLOAD);
        insertTimeline(incidentId, "'HYPOTHESIS_CREATED'", "'AI_RUNTIME'", "NULL", PAYLOAD);

        insertIncident("INC-20260926-0002", "'RESOLVED'", NOW);
        insertIncident("INC-20260926-10000", "'CANCELLED'", "NULL");
    }

    @Test
    void rejectsDuplicates() throws SQLException {
        long incidentId = insertIncident("INC-20260926-0001", "'CREATED'", "NULL");
        assertSqlError(() -> insertIncident("INC-20260926-0001", "'CREATED'", "NULL"), ER_DUP_ENTRY, "uk_incident_key");

        insertAffected(incidentId, resourceId);
        assertSqlError(() -> insertAffected(incidentId, resourceId), ER_DUP_ENTRY, "PRIMARY");

        insertInvestigation(incidentId);
        assertSqlError(() -> insertInvestigation(incidentId), ER_DUP_ENTRY, "uk_investigation_incident");
    }

    @Test
    void foreignKeysRejectOrphansAndRestrictDelete() throws SQLException {
        assertSqlError(
                () -> execute("INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status,"
                        + " created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES"
                        + " ('INC-20260926-0009', 999999, 'T', 'I', 'CREATED', 'MANUAL', 'demo-user', " + NOW
                        + ", " + NOW + ", " + NOW + ", " + NOW + ")"),
                ER_NO_REFERENCED_ROW,
                "fk_incident_system");
        long incidentId = insertIncident("INC-20260926-0001", "'CREATED'", "NULL");
        assertSqlError(
                () -> insertAffected(999_999L, resourceId),
                ER_NO_REFERENCED_ROW,
                "fk_incident_affected_resource_incident");
        assertSqlError(
                () -> insertAffected(incidentId, 999_999L),
                ER_NO_REFERENCED_ROW,
                "fk_incident_affected_resource_resource");
        assertSqlError(() -> insertInvestigation(999_999L), ER_NO_REFERENCED_ROW, "fk_investigation_incident");
        assertSqlError(
                () -> insertTimeline(999_999L, "'INCIDENT_CREATED'", "'USER'", "NULL", PAYLOAD),
                ER_NO_REFERENCED_ROW,
                "fk_incident_timeline_event_incident");

        insertAffected(incidentId, resourceId);
        insertInvestigation(incidentId);
        insertTimeline(incidentId, "'INCIDENT_CREATED'", "'USER'", "'demo-user'", PAYLOAD);
        assertSqlError(
                () -> execute("DELETE FROM managed_system WHERE id = " + systemId),
                ER_ROW_IS_REFERENCED,
                "fk_incident_system");
        assertSqlError(
                () -> execute("DELETE FROM managed_resource WHERE id = " + resourceId),
                ER_ROW_IS_REFERENCED,
                "fk_incident_affected_resource_resource");
        assertSqlError(() -> execute("DELETE FROM incident WHERE id = " + incidentId), ER_ROW_IS_REFERENCED, "fk_");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            status outside the eight states | ck_incident_status | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-20260926-0101', @system_id, 'T', 'I', 'OPEN', 'MANUAL', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            lowercase status | ck_incident_status | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-20260926-0101', @system_id, 'T', 'I', 'created', 'MANUAL', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            status with trailing space | ck_incident_status | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-20260926-0101', @system_id, 'T', 'I', 'CREATED ', 'MANUAL', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            short incident key | ck_incident_key | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-001', @system_id, 'T', 'I', 'CREATED', 'MANUAL', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            lowercase incident key | ck_incident_key | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('inc-20260926-0101', @system_id, 'T', 'I', 'CREATED', 'MANUAL', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            incident key with trailing newline | ck_incident_key | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-20260926-0101\\n', @system_id, 'T', 'I', 'CREATED', 'MANUAL', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            blank title | ck_incident_title | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-20260926-0101', @system_id, ' ', 'I', 'CREATED', 'MANUAL', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            blank impact summary | ck_incident_impact_summary | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-20260926-0101', @system_id, 'T', '', 'CREATED', 'MANUAL', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            unknown created source | ck_incident_created_source | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-20260926-0101', @system_id, 'T', 'I', 'CREATED', 'API', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            lowercase created source | ck_incident_created_source | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-20260926-0101', @system_id, 'T', 'I', 'CREATED', 'manual', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            blank created by | ck_incident_created_by | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-20260926-0101', @system_id, 'T', 'I', 'CREATED', 'MANUAL', ' ', NOW(3), NOW(3), NOW(3), NOW(3))
            resolved time before RESOLVED | ck_incident_resolved_at | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, resolved_at, created_at, updated_at) VALUES ('INC-20260926-0101', @system_id, 'T', 'I', 'VERIFYING', 'MANUAL', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3), NOW(3))
            RESOLVED without resolved time | ck_incident_resolved_at | INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status, created_source, created_by, started_at, detected_at, created_at, updated_at) VALUES ('INC-20260926-0101', @system_id, 'T', 'I', 'RESOLVED', 'MANUAL', 'demo-user', NOW(3), NOW(3), NOW(3), NOW(3))
            run number 0 | ck_investigation_run_no | UPDATE investigation SET current_run_no = 0 WHERE id = @investigation_id
            run count above snapshot limit | ck_investigation_run_capability_count | UPDATE investigation SET current_run_capability_count = 13 WHERE id = @investigation_id
            stop time without actor | ck_investigation_stop_request | UPDATE investigation SET stop_requested_at = NOW(3) WHERE id = @investigation_id
            stop actor without time | ck_investigation_stop_request | UPDATE investigation SET stop_requested_by = 'demo-user' WHERE id = @investigation_id
            blank stop actor | ck_investigation_stop_requested_by | UPDATE investigation SET stop_requested_at = NOW(3), stop_requested_by = ' ' WHERE id = @investigation_id
            zero capability limit | ck_investigation_limits | UPDATE investigation SET max_capability_calls = 0, current_run_capability_count = 0 WHERE id = @investigation_id
            zero duration limit | ck_investigation_limits | UPDATE investigation SET max_duration_seconds = 0 WHERE id = @investigation_id
            zero step timeout | ck_investigation_limits | UPDATE investigation SET agent_step_timeout_seconds = 0 WHERE id = @investigation_id
            zero failure limit | ck_investigation_limits | UPDATE investigation SET max_consecutive_ai_failures = 0 WHERE id = @investigation_id
            lowercase event type | ck_incident_timeline_event_type | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'incident_created', NOW(3), 'USER', 'S', '{"schemaName": "t", "schemaVersion": 1}', NOW(3))
            unknown actor type | ck_incident_timeline_event_actor_type | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'ADMIN', 'S', '{"schemaName": "t", "schemaVersion": 1}', NOW(3))
            lowercase actor type | ck_incident_timeline_event_actor_type | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'user', 'S', '{"schemaName": "t", "schemaVersion": 1}', NOW(3))
            blank actor id | ck_incident_timeline_event_actor_id | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, actor_id, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', '', 'S', '{"schemaName": "t", "schemaVersion": 1}', NOW(3))
            blank summary | ck_incident_timeline_event_summary | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', ' ', '{"schemaName": "t", "schemaVersion": 1}', NOW(3))
            payload array | ck_incident_timeline_event_payload | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', 'S', '[1]', NOW(3))
            payload without schema | ck_incident_timeline_event_payload | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', 'S', '{"evidenceId": 21}', NOW(3))
            payload without schema version | ck_incident_timeline_event_payload | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', 'S', '{"schemaName": "t"}', NOW(3))
            blank schema name | ck_incident_timeline_event_payload | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', 'S', '{"schemaName": " ", "schemaVersion": 1}', NOW(3))
            numeric schema name | ck_incident_timeline_event_payload | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', 'S', '{"schemaName": 7, "schemaVersion": 1}', NOW(3))
            null schema name | ck_incident_timeline_event_payload | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', 'S', '{"schemaName": null, "schemaVersion": 1}', NOW(3))
            schema version 0 | ck_incident_timeline_event_payload | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', 'S', '{"schemaName": "t", "schemaVersion": 0}', NOW(3))
            string schema version | ck_incident_timeline_event_payload | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', 'S', '{"schemaName": "t", "schemaVersion": "1"}', NOW(3))
            fractional schema version | ck_incident_timeline_event_payload | INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload, created_at) VALUES (@incident_id, 'INCIDENT_CREATED', NOW(3), 'USER', 'S', '{"schemaName": "t", "schemaVersion": 1.5}', NOW(3))
            """)
    void checkConstraintsRejectInvalidRows(String scenario, String constraint, String sql) throws SQLException {
        long incidentId = insertIncident("INC-20260926-0001", "'INVESTIGATING'", "NULL");
        long investigationId = insertInvestigation(incidentId);
        execute("SET @system_id = " + systemId + ", @incident_id = " + incidentId + ", @investigation_id = "
                + investigationId);

        assertSqlError(() -> execute(sql), ER_CHECK_CONSTRAINT_VIOLATED, constraint);
    }

    private long insertIncident(String key, String statusSql, String resolvedAtSql) throws SQLException {
        return insert("INSERT INTO incident (incident_key, managed_system_id, title, description, impact_summary,"
                + " status, created_source, created_by, started_at, detected_at, resolved_at, created_at,"
                + " updated_at) VALUES ('" + key + "', " + systemId + ", '短链接跳转明显变慢', NULL,"
                + " '短链接跳转速度明显下降', " + statusSql + ", 'MANUAL', 'demo-user', " + NOW + ", " + NOW + ", "
                + resolvedAtSql + ", " + NOW + ", " + NOW + ")");
    }

    private void insertAffected(long incidentId, long managedResourceId) throws SQLException {
        execute("INSERT INTO incident_affected_resource (incident_id, managed_resource_id, created_at) VALUES ("
                + incidentId + ", " + managedResourceId + ", " + NOW + ")");
    }

    /** 04 §16 默认快照：12 次、480 秒、60 秒、3 次。 */
    private long insertInvestigation(long incidentId) throws SQLException {
        return insert("INSERT INTO investigation (incident_id, started_at, last_activity_at, current_run_no,"
                + " current_run_started_at, max_capability_calls, max_duration_seconds, agent_step_timeout_seconds,"
                + " max_consecutive_ai_failures, created_at, updated_at) VALUES (" + incidentId + ", " + NOW + ", "
                + NOW + ", 1, " + NOW + ", 12, 480, 60, 3, " + NOW + ", " + NOW + ")");
    }

    private long insertTimeline(
            long incidentId, String eventTypeSql, String actorTypeSql, String actorIdSql, String payloadSql)
            throws SQLException {
        return insert("INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type,"
                + " actor_id, summary, payload, correlation_id, created_at) VALUES (" + incidentId + ", "
                + eventTypeSql + ", " + NOW + ", " + actorTypeSql + ", " + actorIdSql + ", '创建故障', " + payloadSql
                + ", 'req_schema-test', " + NOW + ")");
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

    private long insert(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertSqlError(SqlAction action, int errorCode, String constraint) {
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
