package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityDescriptor;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InspectionType;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.capability.CapabilityDescriptorBuilder;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.capability.ProviderBinding;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.investigation.context.InvestigationContextBuilder;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityRegistry;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.RedisResourceBindingV1;
import io.github.ismoyuan.opspilot.infrastructure.ai.AiProtocolCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上的 Provider 解析与 AI 可见能力描述（08 TASK-045～046、06 §16～§22、CAP-INV-005/016）：Provider 按“ACTIVE 且类型受支持”
 * 的候选数确定、绝不随机；Descriptor 只含受控空间，参数域取自受信配置，序列化后的请求不含端点、凭据、查询模板或选择器内容。
 */
@SpringBootTest
@Testcontainers
@Import({
    CapabilityDescriptorBuilder.class,
    CapabilityProviderResolver.class,
    InvestigationContextBuilder.class,
    ClockConfiguration.class
})
class CapabilityDescriptorIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    static final String PROMQL = "sum(rate(http_server_requests_seconds_count{application=\"shortlink-project\"}[1m]))";

    @Autowired
    CapabilityDescriptorBuilder descriptors;

    @Autowired
    CapabilityProviderResolver resolver;

    @Autowired
    CapabilityRegistry registry;

    @Autowired
    InvestigationContextBuilder contexts;

    @Autowired
    ManagedResourceRepository resources;

    @Autowired
    JdbcTemplate jdbc;

    InvestigationFixture fixture;
    long systemId;

    @BeforeEach
    void seed() {
        for (String table : List.of("capability_binding", "resource_binding", "data_source_connection")) {
            jdbc.update("DELETE FROM " + table);
        }
        fixture = InvestigationFixture.reset(jdbc);
        systemId = jdbc.queryForObject(
                "SELECT id FROM managed_system WHERE system_key = 'shortlink-platform'", Long.class);
    }

    // ---------------------------------------------------------------- TASK-046

    /** 唯一 ACTIVE 且类型受支持的绑定被选中，选择器按类型解码；同类型的 DISABLED 连接不参与。 */
    @Test
    void theOnlyActiveSupportedBindingIsResolvedWithATypedSelector() {
        long service = resource("redirect-service", "SERVICE", "ACTIVE");
        bind(
                service,
                connection("prometheus-local", "PROMETHEUS", "ACTIVE"),
                "prometheus.resource.binding",
                prometheus());
        bind(
                service,
                connection("prometheus-old", "PROMETHEUS", "DISABLED"),
                "prometheus.resource.binding",
                prometheus());
        bind(service, connection("loki-local", "LOKI", "ACTIVE"), "loki.resource.binding", loki());

        ProviderBinding provider = resolve(service, CapabilityKey.METRICS_QUERY);

        assertThat(provider.connection().connectionKey()).isEqualTo("prometheus-local");
        assertThat(provider.selector())
                .isInstanceOfSatisfying(
                        PrometheusResourceBindingV1.class,
                        selector -> assertThat(selector.metrics())
                                .containsOnlyKeys("http.request.latency.p99", "http.request.rate"));
    }

    /** 0 个候选：没有绑定、只有 DISABLED 连接或只有其他类型的连接，均为未配置。 */
    @Test
    void zeroCandidatesAreNotConfigured() {
        long bare = resource("bare-service", "SERVICE", "ACTIVE");
        assertFailure(
                bare,
                CapabilityKey.SERVICE_INSPECT,
                ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED,
                "NO_ACTIVE_PROVIDER");

        long service = resource("statistics-consumer", "CONSUMER", "ACTIVE");
        bind(service, connection("docker-off", "DOCKER", "DISABLED"), "docker.resource.binding", docker());
        bind(service, connection("loki-local", "LOKI", "ACTIVE"), "loki.resource.binding", loki());
        assertFailure(
                service,
                CapabilityKey.SERVICE_INSPECT,
                ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED,
                "NO_ACTIVE_PROVIDER");
    }

    /** 多于 1 个 ACTIVE 候选：歧义，绝不随便挑一个（重复解析结果不变）。 */
    @Test
    void multipleActiveCandidatesAreAmbiguousAndNeverPickedAtRandom() {
        long database = resource("shortlink-mysql", "DATABASE", "ACTIVE");
        bind(
                database,
                connection("mysql-a", "MYSQL", "ACTIVE"),
                "mysql.resource.binding",
                "{\"databaseName\": \"shortlink\"}");
        bind(
                database,
                connection("mysql-b", "MYSQL", "ACTIVE"),
                "mysql.resource.binding",
                "{\"databaseName\": \"shortlink\"}");

        for (int i = 0; i < 3; i++) {
            assertFailure(
                    database,
                    CapabilityKey.DATABASE_INSPECT,
                    ErrorCode.CAPABILITY_PROVIDER_AMBIGUOUS,
                    "MULTIPLE_ACTIVE_PROVIDERS");
        }
    }

    /** 唯一候选的选择器须能按其 Provider 类型解码并满足该能力：Schema 不符、载荷非法或缺少 Stream 均视为未配置。 */
    @Test
    void theOnlyCandidateMustCarryAUsableSelector() {
        long mismatched = resource("redirect-service", "SERVICE", "ACTIVE");
        bind(mismatched, connection("prometheus-local", "PROMETHEUS", "ACTIVE"), "loki.resource.binding", loki());
        assertFailure(
                mismatched,
                CapabilityKey.METRICS_QUERY,
                ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED,
                "INVALID_SELECTOR");

        long malformed = resource("statistics-consumer", "CONSUMER", "ACTIVE");
        bind(
                malformed,
                connection("prometheus-two", "PROMETHEUS", "ACTIVE"),
                "prometheus.resource.binding",
                "{\"labels\": {\"app\": \"consumer\"}, \"metrics\": {}}");
        assertFailure(
                malformed,
                CapabilityKey.METRICS_QUERY,
                ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED,
                "INVALID_SELECTOR");

        long cacheOnly = resource("bare-stream", "MESSAGE_QUEUE", "ACTIVE");
        long redis = connection("redis-local", "REDIS", "ACTIVE");
        bind(cacheOnly, redis, "redis.resource.binding", "{}");
        assertFailure(
                cacheOnly,
                CapabilityKey.QUEUE_INSPECT,
                ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED,
                "INCOMPLETE_SELECTOR");

        long stream = fixture.jdbc()
                .queryForObject("SELECT id FROM managed_resource WHERE resource_key = 'statistics-stream'", Long.class);
        bind(stream, redis, "redis.resource.binding", streamSelector());
        assertThat(resolve(stream, CapabilityKey.QUEUE_INSPECT).selector())
                .isInstanceOfSatisfying(
                        RedisResourceBindingV1.class,
                        selector -> assertThat(selector.hasStream()).isTrue());
    }

    // ---------------------------------------------------------------- TASK-045

    /**
     * 只暴露受控空间：本系统 ACTIVE 资源上已启用、已注册、OBSERVE、类型受支持且 Provider 唯一的能力；按 resourceKey、能力键排序。
     * 排除：CHANGE（service.restart）、未注册（shell.exec）、禁用绑定、类型不符、Provider 缺失/歧义/选择器不全、非 ACTIVE 资源、他系统资源。
     */
    @Test
    void descriptorsExposeOnlyTheControlledSpace() {
        seedConfiguredSystem();

        List<CapabilityDescriptor> described = descriptors.describe(fixture.incidentId());

        assertThat(described)
                .extracting(CapabilityDescriptor::resourceKey, CapabilityDescriptor::key)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("redirect-service", "logs.search"),
                        org.assertj.core.groups.Tuple.tuple("redirect-service", "metrics.query"),
                        org.assertj.core.groups.Tuple.tuple("report-mysql", "database.inspect"),
                        org.assertj.core.groups.Tuple.tuple("shortlink-redis", "cache.inspect"),
                        org.assertj.core.groups.Tuple.tuple("statistics-consumer", "service.inspect"),
                        org.assertj.core.groups.Tuple.tuple("statistics-stream", "queue.inspect"));
        assertThat(described)
                .filteredOn(CapabilityDescriptor.MetricsQuery.class::isInstance)
                .singleElement()
                .isEqualTo(new CapabilityDescriptor.MetricsQuery(
                        "metrics.query",
                        id("redirect-service"),
                        "redirect-service",
                        List.of("http.request.latency.p99", "http.request.rate"),
                        List.of(WindowKey.values()),
                        true));
        assertThat(described)
                .filteredOn(CapabilityDescriptor.LogsSearch.class::isInstance)
                .singleElement()
                .isEqualTo(new CapabilityDescriptor.LogsSearch(
                        "logs.search",
                        id("redirect-service"),
                        "redirect-service",
                        List.of(WindowKey.values()),
                        List.of(LogSeverity.values()),
                        5,
                        64));
        assertThat(described)
                .filteredOn(CapabilityDescriptor.DatabaseInspect.class::isInstance)
                .singleElement()
                .isEqualTo(new CapabilityDescriptor.DatabaseInspect(
                        "database.inspect",
                        id("report-mysql"),
                        "report-mysql",
                        List.of(InspectionType.values()),
                        1,
                        20));
    }

    /** 进入调查请求后，序列化结果不含端点、凭据引用、查询模板或选择器内容，并是可往返的 v1 请求。 */
    @Test
    void theSerializedRequestCarriesDescriptorsWithoutConfigurationSecrets() {
        seedConfiguredSystem();
        AiProtocolCodec codec = new AiProtocolCodec();

        InvestigationStepRequest request =
                contexts.build(fixture.incidentId(), 1).orElseThrow().toRequest(7, "corr_descriptor_test");
        String json = codec.encode(request);

        assertThat(request.availableCapabilities()).hasSize(6);
        for (String marker : List.of(
                "http://prometheus:9090",
                "env://OPSPILOT",
                "http_server_requests_seconds_count",
                "shortlink:stats",
                "stats-consumer-group",
                "shortlink-statistics-consumer",
                "service.restart",
                "shell.exec")) {
            assertThat(json).as(marker).doesNotContain(marker);
        }
        assertThat(codec.decode(json, InvestigationStepRequest.class)).isEqualTo(request);
    }

    /**
     * 配置了超过协议上限（128）的 MetricKey（B13-R1）：该 Prometheus 绑定按选择器非法处理，metrics.query 不进入可执行空间；调查上下文与
     * 其他合法能力照常构造，协议不放宽。恰为 128 字符的键仍可暴露并通过协议往返。
     */
    @Test
    void anOverlongMetricKeyHidesOnlyThatCapability() {
        long service = resource("redirect-service", "SERVICE", "ACTIVE");
        bind(
                service,
                connection("prometheus-local", "PROMETHEUS", "ACTIVE"),
                "prometheus.resource.binding",
                metricsSelector("m".repeat(129)));
        bind(service, connection("loki-local", "LOKI", "ACTIVE"), "loki.resource.binding", loki());
        capability(service, "metrics.query", true);
        capability(service, "logs.search", true);
        long consumer = resource("statistics-consumer", "CONSUMER", "ACTIVE");
        bind(
                consumer,
                connection("prometheus-two", "PROMETHEUS", "ACTIVE"),
                "prometheus.resource.binding",
                metricsSelector("m".repeat(128)));
        capability(consumer, "metrics.query", true);

        assertFailure(
                service, CapabilityKey.METRICS_QUERY, ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED, "INVALID_SELECTOR");
        InvestigationStepRequest request =
                contexts.build(fixture.incidentId(), 1).orElseThrow().toRequest(7, "corr_overlong_metric");

        assertThat(request.availableCapabilities())
                .extracting(CapabilityDescriptor::resourceKey, CapabilityDescriptor::key)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("redirect-service", "logs.search"),
                        org.assertj.core.groups.Tuple.tuple("statistics-consumer", "metrics.query"));
        assertThat(request.availableCapabilities())
                .filteredOn(CapabilityDescriptor.MetricsQuery.class::isInstance)
                .singleElement()
                .satisfies(d -> assertThat(((CapabilityDescriptor.MetricsQuery) d).metricKeys())
                        .containsExactly("m".repeat(128)));
        AiProtocolCodec codec = new AiProtocolCodec();
        assertThat(codec.decode(codec.encode(request), InvestigationStepRequest.class))
                .isEqualTo(request);
    }

    // ---------------------------------------------------------------- fixture

    private void seedConfiguredSystem() {
        long prometheus = connection("prometheus-local", "PROMETHEUS", "ACTIVE");
        long loki = connection("loki-local", "LOKI", "ACTIVE");
        long docker = connection("docker-local", "DOCKER", "ACTIVE");
        long redis = connection("redis-local", "REDIS", "ACTIVE");
        long mysqlA = connection("mysql-a", "MYSQL", "ACTIVE");
        long mysqlB = connection("mysql-b", "MYSQL", "ACTIVE");

        long redirect = resource("redirect-service", "SERVICE", "ACTIVE");
        bind(redirect, prometheus, "prometheus.resource.binding", prometheus());
        bind(redirect, loki, "loki.resource.binding", loki());
        capability(redirect, "metrics.query", true);
        capability(redirect, "logs.search", true);
        capability(redirect, "service.inspect", true); // 无 Docker 绑定
        capability(redirect, "cache.inspect", true); // 类型不符
        capability(redirect, "service.restart", true); // CHANGE
        capability(redirect, "shell.exec", true); // 未注册

        long consumer = resource("statistics-consumer", "CONSUMER", "ACTIVE");
        bind(consumer, docker, "docker.resource.binding", docker());
        bind(consumer, loki, "loki.resource.binding", loki());
        capability(consumer, "service.inspect", true);
        capability(consumer, "service.restart", true);
        capability(consumer, "logs.search", false); // 禁用

        long cache = resource("shortlink-redis", "CACHE", "ACTIVE");
        bind(cache, redis, "redis.resource.binding", "{}");
        capability(cache, "cache.inspect", true);

        long stream = id("statistics-stream");
        bind(stream, redis, "redis.resource.binding", streamSelector());
        capability(stream, "queue.inspect", true);

        long bareStream = resource("bare-stream", "MESSAGE_QUEUE", "ACTIVE");
        bind(bareStream, redis, "redis.resource.binding", "{}");
        capability(bareStream, "queue.inspect", true); // 选择器缺 Stream

        long report = resource("report-mysql", "DATABASE", "ACTIVE");
        bind(report, mysqlA, "mysql.resource.binding", "{\"databaseName\": \"report\"}");
        capability(report, "database.inspect", true);

        long shortlinkMysql = resource("shortlink-mysql", "DATABASE", "ACTIVE");
        bind(shortlinkMysql, mysqlA, "mysql.resource.binding", "{\"databaseName\": \"shortlink\"}");
        bind(shortlinkMysql, mysqlB, "mysql.resource.binding", "{\"databaseName\": \"shortlink\"}");
        capability(shortlinkMysql, "database.inspect", true); // Provider 歧义

        long retired = resource("retired-service", "SERVICE", "DISABLED");
        bind(retired, docker, "docker.resource.binding", docker());
        capability(retired, "service.inspect", true); // 资源非 ACTIVE

        jdbc.update("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('other-platform', 'O', 'DEMO', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        long otherSystem =
                jdbc.queryForObject("SELECT id FROM managed_system WHERE system_key = 'other-platform'", Long.class);
        long foreign = resourceIn(otherSystem, "other-service", "SERVICE", "ACTIVE");
        bind(foreign, docker, "docker.resource.binding", docker());
        capability(foreign, "service.inspect", true); // 他系统
    }

    private ProviderBinding resolve(long resourceId, CapabilityKey key) {
        ManagedResource resource = resources.findById(resourceId).orElseThrow();
        return resolver.resolve(resource, registry.definition(key));
    }

    private void assertFailure(long resourceId, CapabilityKey key, ErrorCode code, String reason) {
        assertThatThrownBy(() -> resolve(resourceId, key)).isInstanceOfSatisfying(ApplicationException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(code);
            assertThat(ex.details()).containsEntry("reason", reason).containsKeys("resourceKey", "capabilityKey");
            assertThat(ex.details().values())
                    .allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain("://"));
        });
    }

    private long resource(String key, String type, String status) {
        return resourceIn(systemId, key, type, status);
    }

    private long resourceIn(long system, String key, String type, String status) {
        jdbc.update(
                "INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                system,
                key,
                key,
                type,
                status);
        return jdbc.queryForObject(
                "SELECT id FROM managed_resource WHERE managed_system_id = ? AND resource_key = ?",
                Long.class,
                system,
                key);
    }

    private long id(String resourceKey) {
        return jdbc.queryForObject(
                "SELECT id FROM managed_resource WHERE managed_system_id = ? AND resource_key = ?",
                Long.class,
                systemId,
                resourceKey);
    }

    private long connection(String key, String providerType, String status) {
        jdbc.update(
                "INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref,"
                        + " config_schema_name, config_schema_version, config_payload, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'test.connection.config', 1, '{}', ?, UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3))",
                key,
                key,
                providerType,
                providerType.equals("PROMETHEUS") ? "http://prometheus:9090" : "tcp://" + key + ":1",
                providerType.equals("MYSQL") ? "env://OPSPILOT_TEST_MYSQL_PASSWORD" : null,
                status);
        return jdbc.queryForObject("SELECT id FROM data_source_connection WHERE connection_key = ?", Long.class, key);
    }

    private void bind(long resourceId, long connectionId, String schemaName, String payload) {
        jdbc.update(
                "INSERT INTO resource_binding (managed_resource_id, data_source_connection_id, selector_schema_name,"
                        + " selector_schema_version, selector_payload, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                resourceId,
                connectionId,
                schemaName,
                payload);
    }

    private void capability(long resourceId, String key, boolean enabled) {
        jdbc.update(
                "INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at, updated_at)"
                        + " VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                resourceId,
                key,
                enabled);
    }

    private static String prometheus() {
        Map<String, String> templates = Map.of("http.request.rate", PROMQL, "http.request.latency.p99", PROMQL);
        StringBuilder metrics = new StringBuilder();
        templates.forEach((key, template) -> metrics.append(metrics.isEmpty() ? "" : ", ")
                .append('"')
                .append(key)
                .append("\": {\"queryTemplate\": \"")
                .append(template.replace("\"", "\\\""))
                .append("\", \"unit\": \"req/s\"}"));
        return "{\"labels\": {\"application\": \"shortlink-project\"}, \"metrics\": {" + metrics + "}}";
    }

    private static String metricsSelector(String metricKey) {
        return "{\"labels\": {\"app\": \"shortlink-project\"}, \"metrics\": {\"" + metricKey
                + "\": {\"queryTemplate\": \"up\", \"unit\": \"ratio\"}}}";
    }

    private static String loki() {
        return "{\"labels\": {\"app\": \"shortlink-project\"}}";
    }

    private static String docker() {
        return "{\"containerName\": \"shortlink-statistics-consumer\"}";
    }

    private static String streamSelector() {
        return "{\"streamKey\": \"shortlink:stats\", \"consumerGroup\": \"stats-consumer-group\"}";
    }
}
