package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.system.ManagedSystemRepository;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.ManagedSystem;
import io.github.ismoyuan.opspilot.domain.system.ResourceStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import io.github.ismoyuan.opspilot.domain.system.SystemStatus;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
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

/** 真实 MySQL 上验证系统仓储的 SQL、行到领域对象映射，以及 Java 枚举与 V001 CHECK 取值一致。 */
@SpringBootTest
@Testcontainers
@Transactional
class MyBatisSystemRepositoryTest {

    /** information_schema 中 CHECK 子句的字符串字面量形如 _utf8mb4\'ACTIVE\'，引号带反斜杠转义。 */
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)\\\\?'");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    ManagedSystemRepository systems;

    @Autowired
    ManagedResourceRepository resources;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void findsSystemByKeyWithAllFields() {
        long id = insertSystem("shortlink-platform", "ACTIVE", "短链接业务系统");

        ManagedSystem system = systems.findBySystemKey("shortlink-platform").orElseThrow();

        assertThat(system)
                .isEqualTo(new ManagedSystem(
                        id, "shortlink-platform", "ShortLink Platform", "短链接业务系统", "DEMO", SystemStatus.ACTIVE, 0));
        assertThat(system.isActive()).isTrue();
        assertThat(systems.findBySystemKey("missing-platform")).isEmpty();
    }

    @Test
    void nullDescriptionAndNonActiveStatusMap() {
        insertSystem("archived-platform", "ARCHIVED", null);

        ManagedSystem system = systems.findBySystemKey("archived-platform").orElseThrow();

        assertThat(system.description()).isNull();
        assertThat(system.status()).isEqualTo(SystemStatus.ARCHIVED);
        assertThat(system.isActive()).isFalse();
    }

    @Test
    void resourceLookupIsScopedToItsSystem() {
        long shortlink = insertSystem("shortlink-platform", "ACTIVE", null);
        long other = insertSystem("other-platform", "ACTIVE", null);
        long redis = insertResource(shortlink, "shortlink-redis", "CACHE", "ACTIVE");
        long otherRedis = insertResource(other, "shortlink-redis", "CACHE", "DISABLED");

        ManagedResource found = resources
                .findBySystemIdAndResourceKey(shortlink, "shortlink-redis")
                .orElseThrow();

        assertThat(found)
                .isEqualTo(new ManagedResource(
                        redis,
                        shortlink,
                        "shortlink-redis",
                        "Name shortlink-redis",
                        ResourceType.CACHE,
                        null,
                        ResourceStatus.ACTIVE,
                        0));
        assertThat(resources.findBySystemIdAndResourceKey(other, "shortlink-redis"))
                .map(ManagedResource::id)
                .contains(otherRedis);
        assertThat(resources.findBySystemIdAndResourceKey(shortlink, "statistics-consumer"))
                .isEmpty();
        assertThat(resources.findById(otherRedis).orElseThrow().isActive()).isFalse();
        assertThat(resources.findById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void listsResourcesOfOneSystemOrderedByKey() {
        long shortlink = insertSystem("shortlink-platform", "ACTIVE", null);
        long other = insertSystem("other-platform", "ACTIVE", null);
        insertResource(shortlink, "statistics-consumer", "CONSUMER", "ACTIVE");
        insertResource(shortlink, "redirect-service", "SERVICE", "ACTIVE");
        insertResource(other, "other-service", "SERVICE", "ACTIVE");

        assertThat(resources.findAllBySystemId(shortlink))
                .extracting(ManagedResource::resourceKey)
                .containsExactly("redirect-service", "statistics-consumer");
        assertThat(resources.findAllBySystemId(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void everyResourceTypeAndStatusRoundTrips() {
        long system = insertSystem("shortlink-platform", "ACTIVE", null);
        for (ResourceType type : ResourceType.values()) {
            for (ResourceStatus status : ResourceStatus.values()) {
                String key = (type.name() + "-" + status.name()).toLowerCase().replace('_', '-');
                long id = insertResource(system, key, type.name(), status.name());

                ManagedResource resource = resources.findById(id).orElseThrow();

                assertThat(resource.resourceType()).isEqualTo(type);
                assertThat(resource.status()).isEqualTo(status);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHORTLINK-REDIS", "Shortlink-redis", "shortlink-rédis", "shortlink-redis "})
    void resourceKeyLookupIsExact(String variant) {
        long system = insertSystem("shortlink-platform", "ACTIVE", null);
        insertResource(system, "shortlink-redis", "CACHE", "ACTIVE");

        assertThat(resources.findBySystemIdAndResourceKey(system, variant)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "ck_managed_system_status, io.github.ismoyuan.opspilot.domain.system.SystemStatus",
        "ck_managed_resource_status, io.github.ismoyuan.opspilot.domain.system.ResourceStatus",
        "ck_managed_resource_type, io.github.ismoyuan.opspilot.domain.system.ResourceType"
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

    private long insertSystem(String key, String status, String description) {
        return insert(
                "INSERT INTO managed_system (system_key, name, description, environment, status, created_at,"
                        + " updated_at) VALUES (?, 'ShortLink Platform', ?, 'DEMO', ?, UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3))",
                key,
                description,
                status);
    }

    private long insertResource(long systemId, String key, String type, String status) {
        return insert(
                "INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                systemId,
                key,
                "Name " + key,
                type,
                status);
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
