package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.system.CapabilityBindingRepository;
import io.github.ismoyuan.opspilot.application.system.DataSourceConnectionRepository;
import io.github.ismoyuan.opspilot.application.system.ResourceBindingRepository;
import io.github.ismoyuan.opspilot.domain.system.CapabilityBinding;
import io.github.ismoyuan.opspilot.domain.system.ConfigSchema;
import io.github.ismoyuan.opspilot.domain.system.ConnectionStatus;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.ResourceBinding;
import io.github.ismoyuan.opspilot.domain.system.SelectorSchema;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 真实 MySQL 上验证数据源连接、资源绑定与能力绑定仓储的 SQL、JSON 载荷与枚举映射。 */
@SpringBootTest
@Testcontainers
@Transactional
class MyBatisBindingRepositoryTest {

    /** information_schema 中 CHECK 子句的字符串字面量形如 _utf8mb4\'ACTIVE\'，引号带反斜杠转义。 */
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)\\\\?'");

    /** MySQL JSON 列按规范化形式返回（键排序、冒号与逗号后空格），用规范化文本插入以便逐字比较。 */
    private static final String PROMETHEUS_SELECTOR = "{\"labels\": {\"application\": \"shortlink-project\"}}";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    DataSourceConnectionRepository connections;

    @Autowired
    ResourceBindingRepository resourceBindings;

    @Autowired
    CapabilityBindingRepository capabilityBindings;

    @Autowired
    JdbcTemplate jdbc;

    long system;

    @BeforeEach
    void createSystem() {
        system = insert("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('shortlink-platform', 'ShortLink', 'DEMO', 'ACTIVE', UTC_TIMESTAMP(3),"
                + " UTC_TIMESTAMP(3))");
    }

    @Test
    void findsConnectionByKeyAndIdWithAllFields() {
        long id = insertConnection(
                "mysql-local",
                "MYSQL",
                "env://OPSPILOT_SHORTLINK_MYSQL_PASSWORD",
                "{\"database\": \"shortlink\", \"username\": \"opspilot_ro\"}",
                "ACTIVE");

        DataSourceConnection connection =
                connections.findByConnectionKey("mysql-local").orElseThrow();

        assertThat(connection)
                .isEqualTo(new DataSourceConnection(
                        id,
                        "mysql-local",
                        "Name mysql-local",
                        ProviderType.MYSQL,
                        "http://mysql-local:1",
                        "env://OPSPILOT_SHORTLINK_MYSQL_PASSWORD",
                        new ConfigSchema("mysql.connection.config", 1),
                        "{\"database\": \"shortlink\", \"username\": \"opspilot_ro\"}",
                        ConnectionStatus.ACTIVE,
                        0));
        assertThat(connection.isActive()).isTrue();
        assertThat(connections.findById(id)).contains(connection);
        assertThat(connections.findByConnectionKey("missing-local")).isEmpty();
        assertThat(connections.findById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void connectionWithoutCredentialAndNonActiveStatusMaps() {
        long id = insertConnection("prometheus-local", "PROMETHEUS", null, "{}", "DISABLED");

        DataSourceConnection connection = connections.findById(id).orElseThrow();

        assertThat(connection.credentialRef()).isNull();
        assertThat(connection.configPayload()).isEqualTo("{}");
        assertThat(connection.status()).isEqualTo(ConnectionStatus.DISABLED);
        assertThat(connection.isActive()).isFalse();
    }

    @Test
    void everyProviderTypeAndStatusRoundTrips() {
        for (ProviderType type : ProviderType.values()) {
            for (ConnectionStatus status : ConnectionStatus.values()) {
                String key = (type.name() + "-" + status.name()).toLowerCase();
                long id = insertConnection(key, type.name(), null, "{}", status.name());

                DataSourceConnection connection = connections.findById(id).orElseThrow();

                assertThat(connection.providerType()).isEqualTo(type);
                assertThat(connection.status()).isEqualTo(status);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"MYSQL-LOCAL", "Mysql-local", "mysql-lócal"})
    void connectionKeyLookupIsExact(String variant) {
        insertConnection("mysql-local", "MYSQL", null, "{}", "ACTIVE");

        assertThat(connections.findByConnectionKey(variant)).isEmpty();
    }

    @Test
    void resourceBindingsAreScopedToResourceAndOrderedByConnection() {
        long project = insertResource("shortlink-project", "SERVICE");
        long other = insertResource("shortlink-gateway", "SERVICE");
        long loki = insertConnection("loki-local", "LOKI", null, "{}", "ACTIVE");
        long prometheus = insertConnection("prometheus-local", "PROMETHEUS", null, "{}", "ACTIVE");
        long lokiBinding = insertResourceBinding(
                project, loki, "loki.resource.selector", "{\"labels\": {\"app\": \"shortlink-project\"}}");
        long prometheusBinding =
                insertResourceBinding(project, prometheus, "prometheus.resource.selector", PROMETHEUS_SELECTOR);
        insertResourceBinding(other, prometheus, "prometheus.resource.selector", "{\"labels\": {}}");

        assertThat(resourceBindings.findAllByResourceId(project))
                .containsExactly(
                        new ResourceBinding(
                                lokiBinding,
                                project,
                                loki,
                                new SelectorSchema("loki.resource.selector", 1),
                                "{\"labels\": {\"app\": \"shortlink-project\"}}"),
                        new ResourceBinding(
                                prometheusBinding,
                                project,
                                prometheus,
                                new SelectorSchema("prometheus.resource.selector", 1),
                                PROMETHEUS_SELECTOR));
        assertThat(resourceBindings.findAllByResourceId(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void capabilityBindingLookupIsScopedAndKeepsEnabledFlag() {
        long consumer = insertResource("statistics-consumer", "CONSUMER");
        long redis = insertResource("shortlink-redis", "CACHE");
        long inspect = insertCapabilityBinding(consumer, "service.inspect", true);
        long restart = insertCapabilityBinding(consumer, "service.restart", false);
        insertCapabilityBinding(redis, "cache.inspect", true);

        assertThat(capabilityBindings.findByResourceIdAndCapabilityKey(consumer, "service.inspect"))
                .contains(new CapabilityBinding(inspect, consumer, "service.inspect", true));
        assertThat(capabilityBindings.findByResourceIdAndCapabilityKey(consumer, "service.restart"))
                .contains(new CapabilityBinding(restart, consumer, "service.restart", false));
        assertThat(capabilityBindings.findByResourceIdAndCapabilityKey(consumer, "cache.inspect"))
                .isEmpty();
        assertThat(capabilityBindings.findAllByResourceId(consumer))
                .extracting(CapabilityBinding::capabilityKey)
                .containsExactly("service.inspect", "service.restart");
        assertThat(capabilityBindings.findAllByResourceId(Long.MAX_VALUE)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SERVICE.INSPECT", "Service.inspect", "sérvice.inspect"})
    void capabilityKeyLookupIsExact(String variant) {
        long consumer = insertResource("statistics-consumer", "CONSUMER");
        insertCapabilityBinding(consumer, "service.inspect", true);

        assertThat(capabilityBindings.findByResourceIdAndCapabilityKey(consumer, variant))
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "ck_data_source_connection_provider, io.github.ismoyuan.opspilot.domain.system.ProviderType",
        "ck_data_source_connection_status, io.github.ismoyuan.opspilot.domain.system.ConnectionStatus"
    })
    void javaEnumMatchesDatabaseCheck(String constraint, Class<? extends Enum<?>> enumType) {
        String clause = jdbc.queryForObject(
                "SELECT check_clause FROM information_schema.check_constraints"
                        + " WHERE constraint_schema = DATABASE() AND constraint_name = ?",
                String.class,
                constraint);

        List<String> allowed =
                QUOTED.matcher(clause).results().map(m -> m.group(1)).toList();
        List<String> javaNames =
                Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList();

        assertThat(allowed).containsExactlyInAnyOrderElementsOf(javaNames);
    }

    private long insertResource(String key, String type) {
        return insert(
                "INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3))",
                system,
                key,
                "Name " + key,
                type);
    }

    private long insertConnection(String key, String providerType, String credentialRef, String config, String status) {
        return insert(
                "INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref,"
                        + " config_schema_name, config_schema_version, config_payload, status, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                key,
                "Name " + key,
                providerType,
                "http://" + key + ":1",
                credentialRef,
                providerType.toLowerCase() + ".connection.config",
                config,
                status);
    }

    private long insertResourceBinding(long resourceId, long connectionId, String schemaName, String selector) {
        return insert(
                "INSERT INTO resource_binding (managed_resource_id, data_source_connection_id,"
                        + " selector_schema_name, selector_schema_version, selector_payload, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 1, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                resourceId,
                connectionId,
                schemaName,
                selector);
    }

    private long insertCapabilityBinding(long resourceId, String capabilityKey, boolean enabled) {
        return insert(
                "INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at,"
                        + " updated_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                resourceId,
                capabilityKey,
                enabled);
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    var statement = connection.prepareStatement(sql, new String[] {"id"});
                    for (int i = 0; i < args.length; i++) {
                        statement.setObject(i + 1, args[i]);
                    }
                    return statement;
                },
                keys);
        return keys.getKey().longValue();
    }
}
