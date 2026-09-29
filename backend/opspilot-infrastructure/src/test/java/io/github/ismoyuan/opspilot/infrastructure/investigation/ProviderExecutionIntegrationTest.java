package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CacheInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.DatabaseInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InspectionType;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogsSearchArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.QueueInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RequestCapability;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ServiceInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAdmissionService;
import io.github.ismoyuan.opspilot.application.capability.CapabilityExecutionResult;
import io.github.ismoyuan.opspilot.application.capability.CapabilityExecutionService;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.capability.CapabilityResultRecorder;
import io.github.ismoyuan.opspilot.application.capability.DuplicateGuard;
import io.github.ismoyuan.opspilot.application.capability.ObserveResultPipeline;
import io.github.ismoyuan.opspilot.application.capability.extract.ObservationExtractor;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderCapabilityInvoker;
import io.github.ismoyuan.opspilot.application.capability.raw.RawResultStore;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.secret.SecretNotFoundException;
import io.github.ismoyuan.opspilot.application.secret.SecretResolver;
import io.github.ismoyuan.opspilot.application.secret.SecretValue;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 08 TASK-052～053 端到端：真实 MySQL 上的准入 → 生产 CapabilityInvoker（按能力分派）→ 真实 Prometheus / Loki Provider → 结果管线
 * （脱敏、编码、提取、原始结果）→ 结果事务 → Observation；Provider 失败记 06 §35 错误码、不产生 Observation 且不退还预算；尚无 Provider
 * 的能力如实记失败。调查循环仍未接入执行服务（TASK-058）。
 */
@SpringBootTest
@Testcontainers
@Import({
    CapabilityAdmissionService.class,
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    DuplicateGuard.class,
    CapabilityResultRecorder.class,
    CapabilityExecutionService.class,
    Sanitizer.class,
    ObservationExtractor.class,
    ObserveResultPipeline.class,
    ProviderCapabilityInvoker.class,
    ClockConfiguration.class,
    ProviderExecutionIntegrationTest.TestSecrets.class
})
class ProviderExecutionIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @Container
    static final GenericContainer<?> PROMETHEUS = new GenericContainer<>("prom/prometheus:v3.5.0")
            .withExposedPorts(9090)
            .withCopyToContainer(Transferable.of("""
                            global:
                              scrape_interval: 1s
                            scrape_configs:
                              - job_name: prometheus
                                static_configs:
                                  - targets: ['localhost:9090']
                            """), "/etc/prometheus/prometheus.yml")
            .waitingFor(Wait.forHttp("/-/ready").forPort(9090));

    @Container
    static final GenericContainer<?> LOKI = new GenericContainer<>("grafana/loki:3.5.3")
            .withExposedPorts(3100)
            .waitingFor(Wait.forHttp("/ready").forPort(3100).withStartupTimeout(Duration.ofMinutes(2)));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

    static final Path RAW_RESULTS = createTempDirectory();

    /** 只为本测试解析业务 MySQL 的只读口令；其他引用照常不存在。 */
    @TestConfiguration
    static class TestSecrets {
        @Bean
        @Primary
        SecretResolver testSecretResolver() {
            return reference -> {
                if (reference.equals("env://OPSPILOT_TEST_MYSQL_PASSWORD")) {
                    return new SecretValue(MYSQL.getPassword());
                }
                throw new SecretNotFoundException(SecretNotFoundException.Reason.NOT_FOUND, "missing " + reference);
            };
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("opspilot.capability.raw-result-directory", RAW_RESULTS::toString);
        registry.add("opspilot.capability.providers.prometheus.min-step", () -> "1s");
    }

    @BeforeAll
    static void prepareProviders() throws Exception {
        Instant now = Instant.now();
        List<List<String>> values = List.of(
                List.of(nanos(now.minusSeconds(120)), "ERROR Redis command timed out after 2000 ms password=hunter2"),
                List.of(nanos(now.minusSeconds(90)), "ERROR Redis command timed out after 2100 ms password=hunter2"),
                List.of(nanos(now.minusSeconds(60)), "WARN Connection refused to 10.0.0.7:6379"));
        String body = JsonMapper.builder()
                .build()
                .writeValueAsString(Map.of(
                        "streams", List.of(Map.of("stream", Map.of("app", "shortlink-project"), "values", values))));
        HttpResponse<String> pushed = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(endpoint(LOKI, 3100) + "/loki/api/v1/push"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(pushed.statusCode()).isEqualTo(204);
        awaitPrometheusHistory();
        REDIS.execInContainer(
                "redis-cli", "XGROUP", "CREATE", "shortlink:stats", "stats-consumer-group", "$", "MKSTREAM");
        REDIS.execInContainer("redis-cli", "XADD", "shortlink:stats", "*", "payload", "secret-payload");
    }

    /**
     * 等到 Prometheus 已抓取自身至少 10 次（约 10 秒数据）：15 分钟窗口的步长为 3.75 秒，数据过短时可能没有任何求值点落在首次抓取之后。
     */
    static void awaitPrometheusHistory() throws Exception {
        URI query = URI.create(endpoint(PROMETHEUS, 9090)
                + "/api/v1/query?query=count_over_time%28up%7Bjob%3D%22prometheus%22%7D%5B1m%5D%29");
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            String body = HttpClient.newHttpClient()
                    .send(HttpRequest.newBuilder(query).build(), HttpResponse.BodyHandlers.ofString())
                    .body();
            var value = JsonMapper.builder().build().readTree(body).at("/data/result/0/value/1");
            if (!value.isMissingNode() && Double.parseDouble(value.asString()) >= 10) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Prometheus did not collect enough samples in time");
    }

    @AfterAll
    static void removeRawResults() throws IOException {
        FileSystemUtils.deleteRecursively(RAW_RESULTS);
    }

    @Autowired
    CapabilityExecutionService execution;

    @Autowired
    SchemaCodecRegistry codecs;

    @Autowired
    RawResultStore rawResults;

    @Autowired
    JdbcTemplate jdbc;

    InvestigationFixture fixture;
    long incident;
    long service;
    long cache;
    long database;

    @BeforeEach
    void seed() {
        for (String table : List.of("capability_binding", "resource_binding", "data_source_connection")) {
            jdbc.update("DELETE FROM " + table);
        }
        fixture = InvestigationFixture.reset(jdbc);
        incident = fixture.incidentId();
        long system = jdbc.queryForObject("SELECT id FROM managed_system", Long.class);
        jdbc.update("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) VALUES (" + system + ", 'redirect-service', 'Redirect', 'SERVICE',"
                + " 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        service = jdbc.queryForObject(
                "SELECT id FROM managed_resource WHERE resource_key = 'redirect-service'", Long.class);
        long prometheus = connection("prometheus-local", "PROMETHEUS", endpoint(PROMETHEUS, 9090));
        long loki = connection("loki-local", "LOKI", endpoint(LOKI, 3100));
        long redis = connection("redis-local", "REDIS", "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        long mysql = connection(
                "mysql-readonly",
                "MYSQL",
                "mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306),
                "env://OPSPILOT_TEST_MYSQL_PASSWORD",
                "{\"username\": \"" + MYSQL.getUsername() + "\"}");
        long docker = connection("docker-local", "DOCKER", "unix:///var/run/docker.sock");
        cache = resource(system, "shortlink-redis", "CACHE");
        database = resource(system, "shortlink-mysql", "DATABASE");
        bind(cache, redis, "redis.resource.binding", "{}");
        bind(database, mysql, "mysql.resource.binding", "{\"databaseName\": \"test\"}");
        bind(service, docker, "docker.resource.binding", "{\"containerName\": \"shortlink-redirect\"}");
        capability(cache, "cache.inspect");
        capability(database, "database.inspect");
        capability(service, "service.inspect");
        bind(
                service,
                prometheus,
                "prometheus.resource.binding",
                "{\"labels\": {\"job\": \"prometheus\"}, \"metrics\": {"
                        + "\"scrape.targets.up\": {\"queryTemplate\": \"sum(up{job=\\\"prometheus\\\"})\", \"unit\": \"targets\"},"
                        + "\"broken.template\": {\"queryTemplate\": \"sum(\", \"unit\": \"count\"}}}");
        bind(service, loki, "loki.resource.binding", "{\"labels\": {\"app\": \"shortlink-project\"}}");
        bind(
                fixture.streamId(),
                redis,
                "redis.resource.binding",
                "{\"streamKey\": \"shortlink:stats\", \"consumerGroup\": \"stats-consumer-group\"}");
        capability(service, "metrics.query");
        capability(service, "logs.search");
        capability(fixture.streamId(), "queue.inspect");
    }

    @Test
    void aRealMetricsQueryIsRecordedAsAResultAndAnObservation() {
        CapabilityExecutionResult result = execution.execute(
                incident,
                1,
                new RequestCapability.MetricsQuery(
                        service, new MetricsQueryArgumentsV1("scrape.targets.up", WindowKey.LAST_15_MIN, false), "p"));

        assertThat(result).isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        long id = ((CapabilityExecutionResult.Succeeded) result).invocationId();
        Map<String, Object> call = call(id);
        assertThat(call)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("response_schema_name", "metrics.query.result");
        MetricsQueryResultV1 payload =
                codecs.decode("metrics.query.result", 1, (String) call.get("payload"), MetricsQueryResultV1.class);
        assertThat(payload.sampleCount()).isPositive();
        assertThat(payload.latest()).isEqualTo(1.0);
        assertThat(rawResults.read((String) call.get("raw_result_ref"))).startsWith("metric scrape.targets.up\n");
        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(observation_kind, '|', schema_name, '|', summary) FROM observation"
                                + " WHERE capability_invocation_id = ? AND investigation_id = ? AND managed_resource_id = ?",
                        String.class,
                        id,
                        fixture.investigationId(),
                        service))
                .singleElement()
                .asString()
                .startsWith("METRIC|metric.observation|scrape.targets.up 在 ");
    }

    @Test
    void aRealLogSearchIsSanitizedBeforeItIsPersisted() {
        CapabilityExecutionResult result = execution.execute(
                incident,
                1,
                new RequestCapability.LogsSearch(
                        service,
                        new LogsSearchArgumentsV1(
                                WindowKey.LAST_15_MIN, List.of(LogSeverity.ERROR, LogSeverity.WARN), List.of()),
                        "p"));

        assertThat(result).isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        long id = ((CapabilityExecutionResult.Succeeded) result).invocationId();
        Map<String, Object> call = call(id);
        assertThat((String) call.get("payload")).contains("[REDACTED]").doesNotContain("hunter2");
        assertThat(rawResults.read((String) call.get("raw_result_ref")))
                .contains("password=[REDACTED]")
                .doesNotContain("hunter2");
        assertThat(jdbc.queryForList(
                        "SELECT summary FROM observation WHERE capability_invocation_id = ? ORDER BY id",
                        String.class,
                        id))
                .hasSize(2)
                .first()
                .asString()
                .startsWith("ERROR 日志模式「ERROR Redis command timed out after <NUM> ms password=[REDACTED]」")
                .contains("出现 2 次");
    }

    /** B17：Redis（cache.inspect、queue.inspect）与 MySQL（database.inspect）经生产 Invoker 落账为结果与 Observation，不含消息正文。 */
    @Test
    void realRedisAndMySqlInspectionsAreRecorded() {
        List<CapabilityExecutionResult> results = List.of(
                execution.execute(
                        incident, 1, new RequestCapability.CacheInspect(cache, new CacheInspectArgumentsV1(), "p")),
                execution.execute(
                        incident,
                        1,
                        new RequestCapability.QueueInspect(fixture.streamId(), new QueueInspectArgumentsV1(), "p")),
                execution.execute(
                        incident,
                        1,
                        new RequestCapability.DatabaseInspect(
                                database, new DatabaseInspectArgumentsV1(InspectionType.SERVER_SUMMARY, null), "p")));

        assertThat(results)
                .allSatisfy(result -> assertThat(result).isInstanceOf(CapabilityExecutionResult.Succeeded.class));
        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(ci.capability_key, '|', ci.status, '|', o.observation_kind, '|', o.schema_name)"
                                + " FROM capability_invocation ci JOIN observation o ON o.capability_invocation_id = ci.id"
                                + " ORDER BY ci.id",
                        String.class))
                .containsExactly(
                        "cache.inspect|SUCCEEDED|CACHE_STATUS|cache-status.observation",
                        "queue.inspect|SUCCEEDED|QUEUE_STATUS|queue-status.observation",
                        "database.inspect|SUCCEEDED|DATABASE_STATUS|database-status.observation");
        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(CAST(ci.response_payload AS CHAR), o.summary, CAST(o.payload AS CHAR))"
                                + " FROM capability_invocation ci JOIN observation o ON o.capability_invocation_id = ci.id",
                        String.class))
                .allSatisfy(text -> assertThat(text).doesNotContain("secret-payload"));
        assertThat(jdbc.queryForObject(
                        "SELECT o.summary FROM observation o JOIN capability_invocation ci ON ci.id = o.capability_invocation_id"
                                + " WHERE ci.capability_key = 'queue.inspect'",
                        String.class))
                .startsWith("本次采样 Stream 长度 1")
                .contains("尚未投递积压 1");
    }

    /** Provider 失败：06 §35 错误码与固定文案，不产生 Observation，预算不退还；尚无 Provider 的能力如实记失败。 */
    @Test
    void providerFailuresAreRecordedWithoutObservations() {
        CapabilityExecutionResult rejected = execution.execute(
                incident,
                1,
                new RequestCapability.MetricsQuery(
                        service, new MetricsQueryArgumentsV1("broken.template", WindowKey.LAST_15_MIN, false), "p"));
        // service.inspect 的 Provider 属 TASK-057
        CapabilityExecutionResult missing = execution.execute(
                incident, 1, new RequestCapability.ServiceInspect(service, new ServiceInspectArgumentsV1(), "p"));

        assertThat(rejected).isInstanceOf(CapabilityExecutionResult.Failed.class);
        assertThat(missing).isInstanceOf(CapabilityExecutionResult.Failed.class);
        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(status, '|', error_code, '|', error_message) FROM capability_invocation ORDER BY id",
                        String.class))
                .containsExactly(
                        "FAILED|" + ErrorCode.QUERY_REJECTED + "|Provider answered HTTP 400",
                        "FAILED|CAPABILITY_INVOCATION_FAILED|No provider is available for this capability");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observation", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT capability_call_count FROM investigation WHERE id = ?",
                        Integer.class,
                        fixture.investigationId()))
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> call(long id) {
        return jdbc.queryForMap(
                "SELECT status, response_schema_name, CAST(response_payload AS CHAR) AS payload, raw_result_ref"
                        + " FROM capability_invocation WHERE id = ?",
                id);
    }

    private long connection(String key, String providerType, String endpoint) {
        return connection(key, providerType, endpoint, null, "{}");
    }

    private long connection(String key, String providerType, String endpoint, String credentialRef, String config) {
        jdbc.update(
                "INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref,"
                        + " config_schema_name, config_schema_version, config_payload, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 1, ?, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                key,
                key,
                providerType,
                endpoint,
                credentialRef,
                providerType.toLowerCase() + ".connection.config",
                config);
        return jdbc.queryForObject("SELECT id FROM data_source_connection WHERE connection_key = ?", Long.class, key);
    }

    private long resource(long system, String key, String type) {
        jdbc.update(
                "INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                system,
                key,
                key,
                type);
        return jdbc.queryForObject("SELECT id FROM managed_resource WHERE resource_key = ?", Long.class, key);
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

    private void capability(long resourceId, String key) {
        jdbc.update(
                "INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at, updated_at)"
                        + " VALUES (?, ?, TRUE, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                resourceId,
                key);
    }

    private static String endpoint(GenericContainer<?> container, int port) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(port);
    }

    private static String nanos(Instant instant) {
        return Long.toString(instant.getEpochSecond() * 1_000_000_000L + instant.getNano());
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("opspilot-provider-raw").toRealPath();
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
