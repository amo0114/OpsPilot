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

/** 在真实 MySQL 上执行 V001，并验证 04 §7～§11 的 FK、UNIQUE 与 CHECK（07 §108～§109，不用 H2）。 */
@Testcontainers
class SystemIntegrationSchemaTest {

    private static final int ER_DUP_ENTRY = 1062;
    private static final int ER_ROW_IS_REFERENCED = 1451;
    private static final int ER_NO_REFERENCED_ROW = 1452;
    private static final int ER_CHECK_CONSTRAINT_VIOLATED = 3819;
    private static final String NOW = "'2026-09-26 00:00:00.000'";

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

    @BeforeEach
    void openTransaction() throws SQLException {
        connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void migrationCreatesTheFiveSystemIntegrationTables() throws SQLException {
        assertThat(migration.success).isTrue();
        assertThat(migration.migrations)
                .anySatisfy(applied -> assertThat(applied.version).isEqualTo("001"));

        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                var rs = statement.executeQuery("SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'"
                        + " ORDER BY table_name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        assertThat(tables)
                .contains(
                        "capability_binding",
                        "data_source_connection",
                        "managed_resource",
                        "managed_system",
                        "resource_binding");
    }

    @Test
    void acceptsAValidSystemIntegrationGraph() throws SQLException {
        long systemId = insertSystem("shortlink-platform");
        long resourceId = insertResource(systemId, "statistics-consumer");
        long connectionId = insertConnection("docker-local", "'env://OPSPILOT_DOCKER_TOKEN'");
        execute("INSERT INTO resource_binding (managed_resource_id, data_source_connection_id, selector_schema_name,"
                + " selector_schema_version, selector_payload, created_at, updated_at) VALUES ("
                + resourceId + ", " + connectionId + ", 'docker.selector', 1,"
                + " '{\"containerName\":\"statistics-consumer\"}', " + NOW + ", " + NOW + ")");
        insertCapability(resourceId, "service.inspect");
        insertCapability(resourceId, "service.restart");

        long otherSystemId = insertSystem("another-platform");
        insertResource(otherSystemId, "statistics-consumer");
    }

    @Test
    void rejectsDuplicateKeysAndBindings() throws SQLException {
        long systemId = insertSystem("shortlink-platform");
        assertSqlError(() -> insertSystem("shortlink-platform"), ER_DUP_ENTRY, "uk_managed_system_key");

        long resourceId = insertResource(systemId, "shortlink-redis");
        assertSqlError(
                () -> insertResource(systemId, "shortlink-redis"), ER_DUP_ENTRY, "uk_managed_resource_system_key");

        insertConnection("redis-local", "NULL");
        assertSqlError(() -> insertConnection("redis-local", "NULL"), ER_DUP_ENTRY, "uk_data_source_connection_key");

        long connectionId = insertConnection("redis-other", "NULL");
        String binding = "INSERT INTO resource_binding (managed_resource_id, data_source_connection_id,"
                + " selector_schema_name, selector_schema_version, selector_payload, created_at, updated_at) VALUES ("
                + resourceId + ", " + connectionId + ", 'redis.selector', 1, '{}', " + NOW + ", " + NOW + ")";
        execute(binding);
        assertSqlError(() -> execute(binding), ER_DUP_ENTRY, "uk_resource_binding_resource_connection");

        insertCapability(resourceId, "cache.inspect");
        assertSqlError(
                () -> insertCapability(resourceId, "cache.inspect"),
                ER_DUP_ENTRY,
                "uk_capability_binding_resource_capability");
    }

    @Test
    void foreignKeysRejectOrphansAndRestrictDelete() throws SQLException {
        assertSqlError(() -> insertResource(999_999L, "orphan"), ER_NO_REFERENCED_ROW, "fk_managed_resource_system");

        long systemId = insertSystem("shortlink-platform");
        long resourceId = insertResource(systemId, "shortlink-api");
        insertCapability(resourceId, "metrics.query");

        assertSqlError(
                () -> execute("DELETE FROM managed_system WHERE id = " + systemId),
                ER_ROW_IS_REFERENCED,
                "fk_managed_resource_system");
        assertSqlError(
                () -> execute("DELETE FROM managed_resource WHERE id = " + resourceId),
                ER_ROW_IS_REFERENCED,
                "fk_capability_binding_resource");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            system status outside ACTIVE/DISABLED/ARCHIVED | ck_managed_system_status | INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at) VALUES ('s1', 'S', 'demo', 'DELETED', NOW(3), NOW(3))
            uppercase system key under ci collation        | ck_managed_system_key    | INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at) VALUES ('ShortLink', 'S', 'demo', 'ACTIVE', NOW(3), NOW(3))
            blank environment                              | ck_managed_system_environment | INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at) VALUES ('s1', 'S', ' ', 'ACTIVE', NOW(3), NOW(3))
            unknown resource type                          | ck_managed_resource_type | INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status, created_at, updated_at) VALUES (@system_id, 'r1', 'R', 'CONTAINER', 'ACTIVE', NOW(3), NOW(3))
            unknown provider type                          | ck_data_source_connection_provider | INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES ('c1', 'C', 'ELASTICSEARCH', 'http://es:9200', 'x.config', 1, '{}', 'ACTIVE', NOW(3), NOW(3))
            plaintext credential instead of reference      | ck_data_source_connection_credential_ref | INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref, config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES ('c1', 'C', 'MYSQL', 'jdbc:mysql://db', 'password123', 'mysql.config', 1, '{}', 'ACTIVE', NOW(3), NOW(3))
            config payload that is not a JSON object       | ck_data_source_connection_payload | INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES ('c1', 'C', 'LOKI', 'http://loki:3100', 'loki.config', 1, '[]', 'ACTIVE', NOW(3), NOW(3))
            config schema version 0                        | ck_data_source_connection_schema_version | INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES ('c1', 'C', 'LOKI', 'http://loki:3100', 'loki.config', 0, '{}', 'ACTIVE', NOW(3), NOW(3))
            capability key that is not domain.action       | ck_capability_binding_key | INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at, updated_at) VALUES (@resource_id, 'run_shell', 1, NOW(3), NOW(3))
            lowercase system status | ck_managed_system_status | INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at) VALUES ('s1', 'S', 'demo', 'active', NOW(3), NOW(3))
            accented system status | ck_managed_system_status | INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at) VALUES ('s1', 'S', 'demo', 'ÁCTIVE', NOW(3), NOW(3))
            lowercase resource type | ck_managed_resource_type | INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status, created_at, updated_at) VALUES (@system_id, 'r1', 'R', 'service', 'ACTIVE', NOW(3), NOW(3))
            lowercase resource status | ck_managed_resource_status | INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status, created_at, updated_at) VALUES (@system_id, 'r1', 'R', 'SERVICE', 'disabled', NOW(3), NOW(3))
            lowercase provider type | ck_data_source_connection_provider | INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref, config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES ('c1', 'C', 'mysql', 'jdbc:mysql://db', NULL, 'mysql.config', 1, '{}', 'ACTIVE', NOW(3), NOW(3))
            lowercase connection status | ck_data_source_connection_status | INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref, config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES ('c1', 'C', 'MYSQL', 'jdbc:mysql://db', NULL, 'mysql.config', 1, '{}', 'archived', NOW(3), NOW(3))
            system status with trailing space | ck_managed_system_status | INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at) VALUES ('s1', 'S', 'demo', 'ACTIVE ', NOW(3), NOW(3))
            resource type with trailing space | ck_managed_resource_type | INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status, created_at, updated_at) VALUES (@system_id, 'r1', 'R', 'SERVICE ', 'ACTIVE', NOW(3), NOW(3))
            resource status with trailing space | ck_managed_resource_status | INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status, created_at, updated_at) VALUES (@system_id, 'r1', 'R', 'SERVICE', 'DISABLED ', NOW(3), NOW(3))
            provider type with trailing space | ck_data_source_connection_provider | INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref, config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES ('c1', 'C', 'MYSQL ', 'jdbc:mysql://db', NULL, 'mysql.config', 1, '{}', 'ACTIVE', NOW(3), NOW(3))
            connection status with trailing space | ck_data_source_connection_status | INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref, config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES ('c1', 'C', 'MYSQL', 'jdbc:mysql://db', NULL, 'mysql.config', 1, '{}', 'ARCHIVED ', NOW(3), NOW(3))
            system key with trailing newline | ck_managed_system_key | INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at) VALUES ('abc\\n', 'S', 'demo', 'ACTIVE', NOW(3), NOW(3))
            resource key with trailing newline | ck_managed_resource_key | INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status, created_at, updated_at) VALUES (@system_id, 'r1\\n', 'R', 'SERVICE', 'ACTIVE', NOW(3), NOW(3))
            connection key with trailing newline | ck_data_source_connection_key | INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref, config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES ('c1\\n', 'C', 'MYSQL', 'jdbc:mysql://db', NULL, 'mysql.config', 1, '{}', 'ACTIVE', NOW(3), NOW(3))
            credential ref with trailing newline | ck_data_source_connection_credential_ref | INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref, config_schema_name, config_schema_version, config_payload, status, created_at, updated_at) VALUES ('c1', 'C', 'MYSQL', 'jdbc:mysql://db', 'env://TOKEN\\n', 'mysql.config', 1, '{}', 'ACTIVE', NOW(3), NOW(3))
            capability key with trailing newline | ck_capability_binding_key | INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at, updated_at) VALUES (@resource_id, 'cache.inspect\\n', 1, NOW(3), NOW(3))
            """)
    void checkConstraintsRejectInvalidRows(String scenario, String constraint, String sql) throws SQLException {
        long systemId = insertSystem("check-platform");
        long resourceId = insertResource(systemId, "check-resource");
        execute("SET @system_id = " + systemId + ", @resource_id = " + resourceId);

        assertSqlError(() -> execute(sql), ER_CHECK_CONSTRAINT_VIOLATED, constraint);
    }

    private long insertSystem(String key) throws SQLException {
        return insert("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('" + key + "', 'Name', 'demo', 'ACTIVE', " + NOW + ", " + NOW + ")");
    }

    private long insertResource(long systemId, String key) throws SQLException {
        return insert("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) VALUES (" + systemId + ", '" + key + "', 'Name', 'SERVICE', 'ACTIVE', "
                + NOW + ", " + NOW + ")");
    }

    private long insertConnection(String key, String credentialRefSql) throws SQLException {
        return insert("INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint,"
                + " credential_ref, config_schema_name, config_schema_version, config_payload, status, created_at,"
                + " updated_at) VALUES ('" + key + "', 'Name', 'DOCKER', 'unix:///var/run/docker.sock', "
                + credentialRefSql + ", 'docker.config', 1, '{}', 'ACTIVE', " + NOW + ", " + NOW + ")");
    }

    private void insertCapability(long resourceId, String capabilityKey) throws SQLException {
        execute("INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at,"
                + " updated_at) VALUES (" + resourceId + ", '" + capabilityKey + "', TRUE, " + NOW + ", " + NOW
                + ")");
    }

    private long insert(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void assertSqlError(SqlAction action, int vendorCode, String constraintName) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(vendorCode);
            assertThat(e.getMessage()).contains(constraintName);
        });
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
