package io.github.ismoyuan.opspilot.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryCriterionV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyCriteriaV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyRecord;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyRepository;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyValidator;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySamplingV1;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.CapabilityBindingRepository;
import io.github.ismoyuan.opspilot.application.system.DataSourceConnectionRepository;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.system.ManagedSystemRepository;
import io.github.ismoyuan.opspilot.application.system.ResourceBindingRepository;
import io.github.ismoyuan.opspilot.domain.system.CapabilityBinding;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.ManagedSystem;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.ResourceBinding;
import io.github.ismoyuan.opspilot.domain.system.ResourceStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import io.github.ismoyuan.opspilot.domain.system.SystemStatus;
import io.github.ismoyuan.opspilot.domain.system.binding.DockerResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.LokiResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.MySqlResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.RedisResourceBindingV1;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 以 demo 的 Flyway locations 在真实 MySQL 上应用 Seed，经仓储与 SchemaCodecRegistry 读回，核对 06 §131 配置。 */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/demo")
@Testcontainers
@Import({RecoveryPolicyValidator.class, CapabilityAccess.class, CapabilityProviderResolver.class})
class ShortLinkDemoSeedTest {

    private static final String SEED = "db/demo/R__shortlink_demo_seed.sql";

    /** V0.1 冻结的 7 个能力（06 §10）。 */
    private static final Set<String> V01_CAPABILITIES = Set.of(
            "metrics.query",
            "logs.search",
            "cache.inspect",
            "database.inspect",
            "queue.inspect",
            "service.inspect",
            "service.restart");

    private static final Map<ProviderType, Class<?>> SELECTOR_TYPES = Map.of(
            ProviderType.PROMETHEUS, PrometheusResourceBindingV1.class,
            ProviderType.LOKI, LokiResourceBindingV1.class,
            ProviderType.REDIS, RedisResourceBindingV1.class,
            ProviderType.MYSQL, MySqlResourceBindingV1.class,
            ProviderType.DOCKER, DockerResourceBindingV1.class);

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
    DataSourceConnectionRepository connections;

    @Autowired
    ResourceBindingRepository resourceBindings;

    @Autowired
    CapabilityBindingRepository capabilityBindings;

    @Autowired
    SchemaCodecRegistry codecs;

    @Autowired
    RecoveryPolicyRepository recoveryPolicies;

    @Autowired
    RecoveryPolicyValidator recoveryPolicyValidator;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void seedsShortLinkSystemAndResources() {
        ManagedSystem system = systems.findBySystemKey("shortlink-platform").orElseThrow();

        assertThat(system.name()).isEqualTo("ShortLink Platform");
        assertThat(system.description()).isEqualTo("短链接业务系统");
        assertThat(system.environment()).isEqualTo("DEMO");
        assertThat(system.status()).isEqualTo(SystemStatus.ACTIVE);

        List<ManagedResource> seeded = resources.findAllBySystemId(system.id());
        assertThat(seeded).allMatch(resource -> resource.status() == ResourceStatus.ACTIVE);
        Map<String, ResourceType> types = new TreeMap<>();
        seeded.forEach(resource -> types.put(resource.resourceKey(), resource.resourceType()));
        assertThat(types)
                .containsExactly(
                        entry("redirect-service", ResourceType.SERVICE),
                        entry("shortlink-mysql", ResourceType.DATABASE),
                        entry("shortlink-redis", ResourceType.CACHE),
                        entry("statistics-consumer", ResourceType.CONSUMER),
                        entry("statistics-stream", ResourceType.MESSAGE_QUEUE));
    }

    @Test
    void seedsConnectionsWithCredentialReferencesOnly() {
        Map<String, ProviderType> expected = Map.of(
                "prometheus-local", ProviderType.PROMETHEUS,
                "loki-local", ProviderType.LOKI,
                "redis-local", ProviderType.REDIS,
                "mysql-readonly", ProviderType.MYSQL,
                "docker-local", ProviderType.DOCKER);

        expected.forEach((key, providerType) -> {
            DataSourceConnection connection =
                    connections.findByConnectionKey(key).orElseThrow();
            assertThat(connection.providerType()).isEqualTo(providerType);
            assertThat(connection.isActive()).isTrue();
            assertThat(connection.configSchema().version()).isEqualTo(1);
        });
        assertThat(connections
                        .findByConnectionKey("mysql-readonly")
                        .orElseThrow()
                        .credentialRef())
                .isEqualTo("env://OPSPILOT_SHORTLINK_MYSQL_PASSWORD");
        assertThat(connections.findByConnectionKey("redis-local").orElseThrow().credentialRef())
                .isEqualTo("env://OPSPILOT_SHORTLINK_REDIS_PASSWORD");
        assertThat(connections
                        .findByConnectionKey("prometheus-local")
                        .orElseThrow()
                        .credentialRef())
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_source_connection", Integer.class))
                .isEqualTo(5);
    }

    /** 每条选择器都能按其 schema 解码为与连接 providerType 对应的 V1 类型。 */
    @Test
    void seedsResourceBindingsThatDecodeToTypedSelectors() {
        Map<String, Set<String>> bindingsByResource = new TreeMap<>();
        Map<String, Object> selectors = new LinkedHashMap<>();
        for (ManagedResource resource : shortLinkResources()) {
            for (ResourceBinding binding : resourceBindings.findAllByResourceId(resource.id())) {
                DataSourceConnection connection =
                        connections.findById(binding.dataSourceConnectionId()).orElseThrow();
                bindingsByResource
                        .computeIfAbsent(resource.resourceKey(), key -> new TreeSet<>())
                        .add(connection.connectionKey());
                selectors.put(
                        resource.resourceKey() + "->" + connection.connectionKey(),
                        codecs.decodeSelector(binding, SELECTOR_TYPES.get(connection.providerType())));
            }
        }

        assertThat(bindingsByResource)
                .containsExactly(
                        entry("redirect-service", Set.of("loki-local", "prometheus-local")),
                        entry("shortlink-mysql", Set.of("mysql-readonly")),
                        entry("shortlink-redis", Set.of("redis-local")),
                        entry("statistics-consumer", Set.of("docker-local", "loki-local")),
                        entry("statistics-stream", Set.of("redis-local")));

        PrometheusResourceBindingV1 prometheus =
                (PrometheusResourceBindingV1) selectors.get("redirect-service->prometheus-local");
        assertThat(prometheus.labels()).containsExactly(entry("application", "shortlink-project"));
        assertThat(prometheus.metrics())
                .containsOnlyKeys(
                        "http.request.latency.p99",
                        "http.request.error_rate",
                        "http.request.rate",
                        "jvm.cpu.usage",
                        "jvm.memory.heap.usage",
                        "db.pool.active",
                        "db.pool.pending",
                        "db.pool.max");
        assertThat(prometheus.metrics().get("http.request.latency.p99").unit()).isEqualTo("ms");
        assertThat(prometheus.metrics().get("http.request.latency.p99").queryTemplate())
                .contains("http_server_requests_seconds_bucket{application=\"shortlink-project\"}");

        assertThat(selectors)
                .contains(
                        entry(
                                "redirect-service->loki-local",
                                new LokiResourceBindingV1(Map.of("app", "shortlink-project"))),
                        entry(
                                "statistics-consumer->docker-local",
                                new DockerResourceBindingV1("shortlink-statistics-consumer")),
                        entry(
                                "statistics-consumer->loki-local",
                                new LokiResourceBindingV1(Map.of("app", "shortlink-statistics-consumer"))),
                        entry("shortlink-redis->redis-local", new RedisResourceBindingV1(null, null)),
                        entry(
                                "statistics-stream->redis-local",
                                new RedisResourceBindingV1("shortlink:stats", "stats-consumer-group")),
                        entry("shortlink-mysql->mysql-readonly", new MySqlResourceBindingV1("shortlink")));
    }

    @Test
    void seedsCapabilityBindingsFromCapabilityContract() {
        Map<String, List<String>> capabilities = new TreeMap<>();
        for (ManagedResource resource : shortLinkResources()) {
            List<CapabilityBinding> bindings = capabilityBindings.findAllByResourceId(resource.id());
            assertThat(bindings).allMatch(CapabilityBinding::enabled);
            capabilities.put(
                    resource.resourceKey(),
                    bindings.stream().map(CapabilityBinding::capabilityKey).toList());
        }

        assertThat(capabilities)
                .containsExactly(
                        entry("redirect-service", List.of("logs.search", "metrics.query")),
                        entry("shortlink-mysql", List.of("database.inspect")),
                        entry("shortlink-redis", List.of("cache.inspect")),
                        entry("statistics-consumer", List.of("logs.search", "service.inspect", "service.restart")),
                        entry("statistics-stream", List.of("queue.inspect")));
        assertThat(capabilities.values().stream().flatMap(List::stream)).allMatch(V01_CAPABILITIES::contains);
    }

    /** 重跑 Seed 不产生重复行，并把被改动的配置收敛回文件内容。 */
    @Test
    void rerunningSeedIsIdempotentAndConverges() throws Exception {
        Map<String, Integer> before = rowCounts();
        jdbc.update("UPDATE managed_resource SET name = 'tampered', status = 'DISABLED'"
                + " WHERE resource_key = 'statistics-consumer'");
        jdbc.update("UPDATE capability_binding SET enabled = FALSE WHERE capability_key = 'service.restart'");
        jdbc.update("UPDATE resource_binding SET selector_payload = JSON_OBJECT('containerName', 'other')"
                + " WHERE selector_schema_name = 'docker.resource.binding'");

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SEED));
        }

        assertThat(rowCounts()).isEqualTo(before);
        ManagedResource consumer = shortLinkResources().stream()
                .filter(resource -> resource.resourceKey().equals("statistics-consumer"))
                .findFirst()
                .orElseThrow();
        assertThat(consumer.name()).isEqualTo("Statistics Consumer");
        assertThat(consumer.isActive()).isTrue();
        assertThat(capabilityBindings.findByResourceIdAndCapabilityKey(consumer.id(), "service.restart"))
                .map(CapabilityBinding::enabled)
                .contains(true);
        assertThat(jdbc.queryForObject(
                        "SELECT selector_payload->>'$.containerName' FROM resource_binding"
                                + " WHERE selector_schema_name = 'docker.resource.binding'",
                        String.class))
                .isEqualTo("shortlink-statistics-consumer");
    }

    /**
     * S3 恢复合同（09 §75）：statistics-consumer 上唯一 ACTIVE 的 v1，按正式 Codec 解码为 B -> C -> D -> A 四项 required，并通过
     * 与激活相同的 ACTIVE 前校验（08 TASK-076）；其他组件没有策略。
     */
    @Test
    void seedsTheS3RecoveryPolicyAsALegalActiveContract() {
        ManagedResource consumer = shortLinkResource("statistics-consumer");
        RecoveryPolicyRecord policy = recoveryPolicies.findActive(consumer.id()).getFirst();

        assertThat(recoveryPolicies.findActive(consumer.id())).hasSize(1);
        assertThat(policy.policyKey()).isEqualTo("statistics-consumer-recovery");
        assertThat(policy.name()).isEqualTo("统计消费者恢复标准");
        assertThat(policy.versionNo()).isEqualTo(1);
        RecoveryPolicyCriteriaV1 criteria = codecs.decode(
                policy.criteriaSchemaName(),
                policy.criteriaSchemaVersion(),
                policy.criteriaPayload(),
                RecoveryPolicyCriteriaV1.class);
        assertThat(criteria.maxDurationSeconds()).isEqualTo(120);
        assertThat(criteria.maxSampleAgeSeconds()).isEqualTo(120);
        assertThat(criteria.criteria())
                .extracting(RecoveryCriterionV1::criterionKey, RecoveryCriterionV1::targetResourceKey)
                .containsExactly(
                        tuple("stream-lag-decreasing", "statistics-stream"),
                        tuple("stream-lag-drained", "statistics-stream"),
                        tuple("stream-pending-healthy", "statistics-stream"),
                        tuple("consumer-running", "statistics-consumer"));
        assertThat(criteria.criteria())
                .extracting(RecoveryCriterionV1::sampling)
                .containsExactly(
                        new RecoverySamplingV1(4, 10, 20),
                        new RecoverySamplingV1(1, 0, null),
                        new RecoverySamplingV1(2, 5, 10),
                        new RecoverySamplingV1(2, 5, 10));
        assertThat(criteria.criteria().getFirst().predicate())
                .isEqualTo(new RecoveryPredicateV1.MonotonicTrend(
                        "lag", RecoveryPredicateV1.TrendDirection.DECREASING, 20.0, true));
        assertThat(criteria.criteria()).allMatch(RecoveryCriterionV1::required);
        recoveryPolicyValidator.validate(consumer, criteria);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recovery_policy", Integer.class))
                .isEqualTo(1);
    }

    /** 策略版本不可改写：Seed 重跑不修改已有版本，资源已有新版本时也不再插入第二个 ACTIVE（01 §28、04 §49）。 */
    @Test
    void rerunningSeedNeverRewritesOrReactivatesARecoveryPolicy() throws Exception {
        long consumer = shortLinkResource("statistics-consumer").id();
        jdbc.update(
                "UPDATE recovery_policy SET status = 'RETIRED', retired_at = UTC_TIMESTAMP(3)"
                        + " WHERE managed_resource_id = ?",
                consumer);
        jdbc.update(
                "INSERT INTO recovery_policy (managed_resource_id, policy_key, name, version_no, criteria_schema_name,"
                        + " criteria_schema_version, criteria_payload, status, created_at, activated_at)"
                        + " SELECT managed_resource_id, policy_key, name, 2, criteria_schema_name,"
                        + " criteria_schema_version, criteria_payload, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)"
                        + " FROM recovery_policy WHERE managed_resource_id = ? AND version_no = 1",
                consumer);
        String before = jdbc.queryForList("SELECT id, version_no, status, retired_at FROM recovery_policy ORDER BY id")
                .toString();

        try {
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(SEED));
            }

            assertThat(jdbc.queryForList("SELECT id, version_no, status, retired_at FROM recovery_policy ORDER BY id")
                            .toString())
                    .isEqualTo(before);
            assertThat(recoveryPolicies.findActive(consumer))
                    .singleElement()
                    .extracting(RecoveryPolicyRecord::versionNo)
                    .isEqualTo(2);
        } finally {
            // 其他用例共享同一数据库，恢复为 Seed 的初始状态
            jdbc.update("DELETE FROM recovery_policy WHERE version_no = 2");
            jdbc.update("UPDATE recovery_policy SET status = 'ACTIVE', retired_at = NULL");
        }
    }

    @Test
    void flywayRecordsSeedAsRepeatableMigration() {
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history"
                                + " WHERE script = 'R__shortlink_demo_seed.sql' AND version IS NULL AND success = 1",
                        Integer.class))
                .isEqualTo(1);
    }

    private ManagedResource shortLinkResource(String resourceKey) {
        return shortLinkResources().stream()
                .filter(resource -> resource.resourceKey().equals(resourceKey))
                .findFirst()
                .orElseThrow();
    }

    private List<ManagedResource> shortLinkResources() {
        return resources.findAllBySystemId(
                systems.findBySystemKey("shortlink-platform").orElseThrow().id());
    }

    private Map<String, Integer> rowCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        for (String table : List.of(
                "managed_system",
                "managed_resource",
                "data_source_connection",
                "resource_binding",
                "capability_binding",
                "recovery_policy")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return counts;
    }
}
