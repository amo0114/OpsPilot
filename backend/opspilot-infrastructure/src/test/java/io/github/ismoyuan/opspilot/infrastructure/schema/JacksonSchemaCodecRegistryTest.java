package io.github.ismoyuan.opspilot.infrastructure.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException.Reason;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ResourceBinding;
import io.github.ismoyuan.opspilot.domain.system.SelectorSchema;
import io.github.ismoyuan.opspilot.domain.system.binding.DockerResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.LokiResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.MySqlResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusMetricBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.RedisResourceBindingV1;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 注册表只按明确 name/version 解码为强类型 record，拒绝未知版本、类型错配与任何不严格的载荷。 */
class JacksonSchemaCodecRegistryTest {

    private static final String PROMETHEUS = PrometheusResourceBindingV1.SCHEMA_NAME;
    private static final String LOKI = LokiResourceBindingV1.SCHEMA_NAME;
    private static final String REDIS = RedisResourceBindingV1.SCHEMA_NAME;
    private static final String MYSQL = MySqlResourceBindingV1.SCHEMA_NAME;
    private static final String DOCKER = DockerResourceBindingV1.SCHEMA_NAME;

    private final JacksonSchemaCodecRegistry registry = new JacksonSchemaCodecRegistry();

    @Test
    void decodesPrometheusBindingWithMetrics() {
        String payload = """
                {"labels": {"application": "shortlink-project"},
                 "metrics": {"http.request.latency.p99": {"queryTemplate": "trusted-template", "unit": "ms"},
                             "http.request.error_rate": {"queryTemplate": "trusted-template-2", "unit": "ratio"}}}
                """;

        assertThat(registry.decode(PROMETHEUS, 1, payload, PrometheusResourceBindingV1.class))
                .isEqualTo(new PrometheusResourceBindingV1(
                        Map.of("application", "shortlink-project"),
                        Map.of(
                                "http.request.latency.p99",
                                new PrometheusMetricBindingV1("trusted-template", "ms"),
                                "http.request.error_rate",
                                new PrometheusMetricBindingV1("trusted-template-2", "ratio"))));
    }

    @Test
    void decodesLokiMySqlAndDockerBindings() {
        assertThat(registry.decode(
                        LOKI, 1, "{\"labels\": {\"app\": \"shortlink-project\"}}", LokiResourceBindingV1.class))
                .isEqualTo(new LokiResourceBindingV1(Map.of("app", "shortlink-project")));
        assertThat(registry.decode(MYSQL, 1, "{\"databaseName\": \"shortlink\"}", MySqlResourceBindingV1.class))
                .isEqualTo(new MySqlResourceBindingV1("shortlink"));
        assertThat(registry.decode(
                        DOCKER,
                        1,
                        "{\"containerName\": \"shortlink-statistics-consumer\"}",
                        DockerResourceBindingV1.class))
                .isEqualTo(new DockerResourceBindingV1("shortlink-statistics-consumer"));
    }

    @Test
    void redisBindingIsEitherPlainCacheOrStream() {
        RedisResourceBindingV1 cache = registry.decode(REDIS, 1, "{}", RedisResourceBindingV1.class);
        RedisResourceBindingV1 stream = registry.decode(
                REDIS,
                1,
                "{\"streamKey\": \"shortlink:stats\", \"consumerGroup\": \"stats-consumer-group\"}",
                RedisResourceBindingV1.class);

        assertThat(cache.hasStream()).isFalse();
        assertThat(stream).isEqualTo(new RedisResourceBindingV1("shortlink:stats", "stats-consumer-group"));
        assertThat(stream.hasStream()).isTrue();
    }

    @Test
    void decodesSelectorOfResourceBinding() {
        ResourceBinding binding = new ResourceBinding(
                1, 2, 3, new SelectorSchema(DOCKER, 1), "{\"containerName\": \"shortlink-redirect\"}");

        assertThat(registry.decodeSelector(binding, DockerResourceBindingV1.class))
                .isEqualTo(new DockerResourceBindingV1("shortlink-redirect"));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({
        "docker.resource.binding, 2",
        "docker.resource.binding, 0",
        "Docker.Resource.Binding, 1",
        "docker.resource.selector, 1",
        "cache.inspect.result, 1"
    })
    void rejectsUnregisteredSchema(String schemaName, int schemaVersion) {
        assertRejected(
                schemaName,
                schemaVersion,
                "{\"containerName\": \"shortlink-redirect\"}",
                DockerResourceBindingV1.class,
                Reason.UNKNOWN_SCHEMA);
    }

    @Test
    void rejectsTypeOtherThanRegistered() {
        assertRejected(
                PROMETHEUS, 1, "{\"labels\": {\"app\": \"x\"}}", LokiResourceBindingV1.class, Reason.TYPE_MISMATCH);
        assertRejected(DOCKER, 1, "{\"containerName\": \"x1\"}", Object.class, Reason.TYPE_MISMATCH);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "malformed JSON | {\"containerName\": ",
                "top-level null | null",
                "top-level array | [\"shortlink-redirect\"]",
                "top-level string | \"shortlink-redirect\"",
                "trailing tokens | {\"containerName\": \"shortlink-redirect\"} {}",
                "unknown field | {\"containerName\": \"shortlink-redirect\", \"command\": \"rm -rf /\"}",
                "duplicate key | {\"containerName\": \"shortlink-redirect\", \"containerName\": \"other\"}",
                "number for string | {\"containerName\": 12345}",
                "boolean for string | {\"containerName\": true}",
                "missing field | {}",
                "explicit null | {\"containerName\": null}",
                "invalid name | {\"containerName\": \"-starts-with-dash\"}",
                "shell metacharacters | {\"containerName\": \"a;reboot\"}"
            })
    void rejectsInvalidDockerPayload(String caseName, String payload) {
        assertRejected(DOCKER, 1, payload, DockerResourceBindingV1.class, Reason.INVALID_PAYLOAD);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "empty labels | {\"labels\": {}, \"metrics\": {\"m.x\": {\"queryTemplate\": \"t\", \"unit\": \"ms\"}}}",
                "reserved label | {\"labels\": {\"__name__\": \"x\"}, \"metrics\": {\"m.x\": {\"queryTemplate\": \"t\","
                        + " \"unit\": \"ms\"}}}",
                "bad label name | {\"labels\": {\"app-name\": \"x\"}, \"metrics\": {\"m.x\": {\"queryTemplate\":"
                        + " \"t\", \"unit\": \"ms\"}}}",
                "blank label value | {\"labels\": {\"app\": \" \"}, \"metrics\": {\"m.x\": {\"queryTemplate\": \"t\","
                        + " \"unit\": \"ms\"}}}",
                "null label value | {\"labels\": {\"app\": null}, \"metrics\": {\"m.x\": {\"queryTemplate\": \"t\","
                        + " \"unit\": \"ms\"}}}",
                "number label value | {\"labels\": {\"app\": 1}, \"metrics\": {\"m.x\": {\"queryTemplate\": \"t\","
                        + " \"unit\": \"ms\"}}}",
                "missing metrics | {\"labels\": {\"app\": \"x\"}}",
                "empty metrics | {\"labels\": {\"app\": \"x\"}, \"metrics\": {}}",
                "bad metric key | {\"labels\": {\"app\": \"x\"}, \"metrics\": {\"HTTP P99\": {\"queryTemplate\":"
                        + " \"t\", \"unit\": \"ms\"}}}",
                "null metric | {\"labels\": {\"app\": \"x\"}, \"metrics\": {\"m.x\": null}}",
                "blank template | {\"labels\": {\"app\": \"x\"}, \"metrics\": {\"m.x\": {\"queryTemplate\": \"\","
                        + " \"unit\": \"ms\"}}}",
                "unknown metric field | {\"labels\": {\"app\": \"x\"}, \"metrics\": {\"m.x\": {\"queryTemplate\":"
                        + " \"t\", \"unit\": \"ms\", \"scale\": 1000}}}"
            })
    void rejectsInvalidPrometheusPayload(String caseName, String payload) {
        assertRejected(PROMETHEUS, 1, payload, PrometheusResourceBindingV1.class, Reason.INVALID_PAYLOAD);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "stream without group | {\"streamKey\": \"shortlink:stats\"}",
                "group without stream | {\"consumerGroup\": \"stats-consumer-group\"}",
                "glob stream key | {\"streamKey\": \"shortlink:*\", \"consumerGroup\": \"g\"}",
                "whitespace stream key | {\"streamKey\": \"shortlink stats\", \"consumerGroup\": \"g\"}",
                "blank group | {\"streamKey\": \"shortlink:stats\", \"consumerGroup\": \"\"}"
            })
    void rejectsInvalidRedisPayload(String caseName, String payload) {
        assertRejected(REDIS, 1, payload, RedisResourceBindingV1.class, Reason.INVALID_PAYLOAD);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "Loki empty labels | loki.resource.binding | {\"labels\": {}}",
                "MySQL quoted name | mysql.resource.binding | {\"databaseName\": \"shortlink`; DROP\"}",
                "MySQL too long | mysql.resource.binding | {\"databaseName\":"
                        + " \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}"
            })
    void rejectsInvalidLokiAndMySqlPayload(String caseName, String schemaName, String payload) {
        Class<?> type = schemaName.equals(LOKI) ? LokiResourceBindingV1.class : MySqlResourceBindingV1.class;
        assertRejected(schemaName, 1, payload, type, Reason.INVALID_PAYLOAD);
    }

    @Test
    void rejectsNullPayload() {
        assertRejected(DOCKER, 1, null, DockerResourceBindingV1.class, Reason.INVALID_PAYLOAD);
    }

    @Test
    void rejectionNeverCarriesPayloadValues() {
        SchemaPayloadException unknownField = catchThrowableOfType(
                SchemaPayloadException.class,
                () -> registry.decode(
                        DOCKER,
                        1,
                        "{\"containerName\": \"x1\", \"password\": \"hunter2-secret\"}",
                        DockerResourceBindingV1.class));
        SchemaPayloadException invalidValue = catchThrowableOfType(
                SchemaPayloadException.class,
                () -> registry.decode(
                        DOCKER, 1, "{\"containerName\": \"hunter2 secret\"}", DockerResourceBindingV1.class));
        SchemaPayloadException wrongType = catchThrowableOfType(
                SchemaPayloadException.class,
                () -> registry.decode(DOCKER, 1, "{\"containerName\": 20260926}", DockerResourceBindingV1.class));

        for (SchemaPayloadException ex : new SchemaPayloadException[] {unknownField, invalidValue, wrongType}) {
            assertThat(ex.getMessage()).doesNotContain("hunter2").doesNotContain("20260926");
            assertThat(ex.getCause()).isNull();
            assertThat(ex.details()).isEmpty();
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
        assertThat(unknownField.getMessage()).contains("password");
        assertThat(invalidValue.getMessage()).contains("containerName is invalid");
    }

    private void assertRejected(String schemaName, int schemaVersion, String payload, Class<?> type, Reason reason) {
        assertThatThrownBy(() -> registry.decode(schemaName, schemaVersion, payload, type))
                .isInstanceOfSatisfying(SchemaPayloadException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(reason);
                    assertThat(ex.schemaName()).isEqualTo(schemaName);
                    assertThat(ex.schemaVersion()).isEqualTo(schemaVersion);
                });
    }
}
