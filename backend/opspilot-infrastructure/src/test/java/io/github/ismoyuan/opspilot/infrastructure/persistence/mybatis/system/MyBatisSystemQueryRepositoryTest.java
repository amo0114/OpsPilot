package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.ismoyuan.opspilot.application.system.query.ResourceCapabilityProjection;
import io.github.ismoyuan.opspilot.application.system.query.ResourceSummaryView;
import io.github.ismoyuan.opspilot.application.system.query.SystemDetailView;
import io.github.ismoyuan.opspilot.application.system.query.SystemQueryRepository;
import io.github.ismoyuan.opspilot.application.system.query.SystemSummaryView;
import io.github.ismoyuan.opspilot.domain.system.ResourceStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import io.github.ismoyuan.opspilot.domain.system.SystemStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

/** 真实 MySQL 上验证 Systems 页面投影：分页顺序、资源计数、精确键匹配与只取已启用能力。 */
@SpringBootTest
@Testcontainers
@Transactional
class MyBatisSystemQueryRepositoryTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    SystemQueryRepository queries;

    @Autowired
    JdbcTemplate jdbc;

    long shortlink;
    long consumer;

    @BeforeEach
    void seed() {
        shortlink = insertSystem("shortlink-platform", "ShortLink Platform", "短链接业务系统", "ACTIVE");
        long other = insertSystem("billing-platform", "Billing", null, "ARCHIVED");
        insertSystem("zeta-platform", "Zeta", null, "DISABLED");
        consumer = insertResource(shortlink, "statistics-consumer", "Statistics Consumer", "CONSUMER", "ACTIVE");
        insertResource(shortlink, "redirect-service", "ShortLink Redirect Service", "SERVICE", "DISABLED");
        insertResource(other, "statistics-consumer", "Other Consumer", "CONSUMER", "ACTIVE");
        insertCapability(consumer, "service.restart", true);
        insertCapability(consumer, "service.inspect", true);
        insertCapability(consumer, "logs.search", false);
    }

    @Test
    void listsSystemsPagedByKeyWithResourceCounts() {
        assertThat(queries.countSystems()).isEqualTo(3);
        assertThat(queries.findSystems(0, 2))
                .containsExactly(
                        new SystemSummaryView("billing-platform", "Billing", "DEMO", SystemStatus.ARCHIVED, 1),
                        new SystemSummaryView(
                                "shortlink-platform", "ShortLink Platform", "DEMO", SystemStatus.ACTIVE, 2));
        assertThat(queries.findSystems(2, 2))
                .extracting(SystemSummaryView::systemKey, SystemSummaryView::resourceCount)
                .containsExactly(tuple("zeta-platform", 0L));
        assertThat(queries.findSystems(4, 2)).isEmpty();
    }

    @Test
    void systemDetailListsOwnResourcesOrderedByKey() {
        SystemDetailView detail = queries.findSystemDetail("shortlink-platform").orElseThrow();

        assertThat(detail)
                .isEqualTo(new SystemDetailView(
                        "shortlink-platform",
                        "ShortLink Platform",
                        "短链接业务系统",
                        "DEMO",
                        SystemStatus.ACTIVE,
                        List.of(
                                new ResourceSummaryView(
                                        "redirect-service",
                                        "ShortLink Redirect Service",
                                        ResourceType.SERVICE,
                                        ResourceStatus.DISABLED),
                                new ResourceSummaryView(
                                        "statistics-consumer",
                                        "Statistics Consumer",
                                        ResourceType.CONSUMER,
                                        ResourceStatus.ACTIVE))));
        assertThat(queries.findSystemDetail("zeta-platform").orElseThrow().resources())
                .isEmpty();
        assertThat(queries.findSystemDetail("missing-platform")).isEmpty();
    }

    @Test
    void resourceProjectionIsScopedToSystemAndKeepsOnlyEnabledCapabilities() {
        ResourceCapabilityProjection found = queries.findResource("shortlink-platform", "statistics-consumer")
                .orElseThrow();

        assertThat(found.resource())
                .isEqualTo(new ResourceSummaryView(
                        "statistics-consumer", "Statistics Consumer", ResourceType.CONSUMER, ResourceStatus.ACTIVE));
        assertThat(found.enabledCapabilityKeys()).containsExactly("service.inspect", "service.restart");
        assertThat(queries.findResource("billing-platform", "statistics-consumer")
                        .orElseThrow()
                        .resource()
                        .name())
                .isEqualTo("Other Consumer");
        assertThat(queries.findResource("shortlink-platform", "shortlink-redis"))
                .isEmpty();
        assertThat(queries.findResource("missing-platform", "statistics-consumer"))
                .isEmpty();
        assertThat(queries.systemExists("shortlink-platform")).isTrue();
        assertThat(queries.systemExists("missing-platform")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHORTLINK-PLATFORM", "Shortlink-platform", "shortlink-plátform"})
    void systemKeyVariantsDoNotMatch(String variant) {
        assertThat(queries.findSystemDetail(variant)).isEmpty();
        assertThat(queries.systemExists(variant)).isFalse();
        assertThat(queries.findResource(variant, "statistics-consumer")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATISTICS-CONSUMER", "Statistics-consumer", "statístics-consumer"})
    void resourceKeyVariantsDoNotMatch(String variant) {
        assertThat(queries.findResource("shortlink-platform", variant)).isEmpty();
    }

    private long insertSystem(String key, String name, String description, String status) {
        return insert(
                "INSERT INTO managed_system (system_key, name, description, environment, status, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'DEMO', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                key,
                name,
                description,
                status);
    }

    private long insertResource(long systemId, String key, String name, String type, String status) {
        return insert(
                "INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                systemId,
                key,
                name,
                type,
                status);
    }

    private void insertCapability(long resourceId, String capabilityKey, boolean enabled) {
        insert(
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
