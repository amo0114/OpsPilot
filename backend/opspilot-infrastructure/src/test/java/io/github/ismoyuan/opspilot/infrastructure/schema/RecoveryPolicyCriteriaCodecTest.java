package io.github.ismoyuan.opspilot.infrastructure.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.QueueInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ServiceInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryCriterionV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryField;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyCriteriaV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.ComparisonOperator;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.FieldEquals;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.MonotonicTrend;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.NumericCompare;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1.TrendDirection;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySamplingV1;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException.Reason;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * recovery.policy.criteria / 1 按 06 §113 / 09 §75 解码为强类型模型并往返；只接受 version 1、注册投影与相容谓词，
 * 各类结构错误在激活前被拒绝（08 TASK-075、07 §97）。
 */
class RecoveryPolicyCriteriaCodecTest {

    /** 09 §75 S3 恢复合同原文（与 06 §113 示例相同）。 */
    static final String S3_CRITERIA = """
            {
              "schemaName": "recovery.policy.criteria",
              "schemaVersion": 1,
              "maxDurationSeconds": 120,
              "maxSampleAgeSeconds": 120,
              "criteria": [
                {
                  "criterionKey": "stream-lag-decreasing",
                  "name": "未投递积压进入并保持健康区间",
                  "capabilityKey": "queue.inspect",
                  "targetResourceKey": "statistics-stream",
                  "arguments": {},
                  "sampling": {"sampleCount": 4, "intervalSeconds": 10, "maxGapSeconds": 20},
                  "predicate": {"type": "MONOTONIC_TREND", "field": "lag", "direction": "DECREASING",
                                "healthyThreshold": 20, "requireFinalHealthy": true},
                  "required": true
                },
                {
                  "criterionKey": "stream-lag-drained",
                  "name": "末次未投递积压达标",
                  "capabilityKey": "queue.inspect",
                  "targetResourceKey": "statistics-stream",
                  "arguments": {},
                  "sampling": {"sampleCount": 1, "intervalSeconds": 0, "maxGapSeconds": null},
                  "predicate": {"type": "NUMERIC_COMPARE", "field": "lag", "operator": "LTE", "value": 20},
                  "required": true
                },
                {
                  "criterionKey": "stream-pending-healthy",
                  "name": "已投递未确认积压保持健康",
                  "capabilityKey": "queue.inspect",
                  "targetResourceKey": "statistics-stream",
                  "arguments": {},
                  "sampling": {"sampleCount": 2, "intervalSeconds": 5, "maxGapSeconds": 10},
                  "predicate": {"type": "NUMERIC_COMPARE", "field": "pendingCount", "operator": "LTE", "value": 20},
                  "required": true
                },
                {
                  "criterionKey": "consumer-running",
                  "name": "消费者持续运行",
                  "capabilityKey": "service.inspect",
                  "targetResourceKey": "statistics-consumer",
                  "arguments": {},
                  "sampling": {"sampleCount": 2, "intervalSeconds": 5, "maxGapSeconds": 10},
                  "predicate": {"type": "FIELD_EQUALS", "field": "runtimeState", "value": "RUNNING"},
                  "required": true
                }
              ]
            }
            """;

    private static final String NAME = RecoveryPolicyCriteriaV1.SCHEMA_NAME;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JacksonSchemaCodecRegistry registry = new JacksonSchemaCodecRegistry();

    @Test
    void theS3ContractDecodesInOrderWithTypedArgumentsAndPredicates() {
        RecoveryPolicyCriteriaV1 criteria = decode(S3_CRITERIA);

        assertThat(criteria.maxDurationSeconds()).isEqualTo(120);
        assertThat(criteria.maxSampleAgeSeconds()).isEqualTo(120);
        assertThat(criteria.criteria())
                .extracting(RecoveryCriterionV1::criterionKey)
                .containsExactly(
                        "stream-lag-decreasing", "stream-lag-drained", "stream-pending-healthy", "consumer-running");
        assertThat(criteria.criteria())
                .extracting(RecoveryCriterionV1::capabilityKey)
                .containsExactly(
                        CapabilityKey.QUEUE_INSPECT,
                        CapabilityKey.QUEUE_INSPECT,
                        CapabilityKey.QUEUE_INSPECT,
                        CapabilityKey.SERVICE_INSPECT);
        assertThat(criteria.criteria())
                .extracting(RecoveryCriterionV1::field)
                .containsExactly(
                        RecoveryField.LAG, RecoveryField.LAG, RecoveryField.PENDING_COUNT, RecoveryField.RUNTIME_STATE);

        RecoveryCriterionV1 decreasing = criteria.criteria().get(0);
        assertThat(decreasing.arguments()).isEqualTo(new QueueInspectArgumentsV1());
        assertThat(decreasing.sampling()).isEqualTo(new RecoverySamplingV1(4, 10, 20));
        assertThat(decreasing.predicate()).isEqualTo(new MonotonicTrend("lag", TrendDirection.DECREASING, 20.0, true));
        assertThat(criteria.criteria().get(1).sampling()).isEqualTo(new RecoverySamplingV1(1, 0, null));
        assertThat(criteria.criteria().get(2).predicate())
                .isEqualTo(new NumericCompare("pendingCount", ComparisonOperator.LTE, 20.0));
        RecoveryCriterionV1 running = criteria.criteria().get(3);
        assertThat(running.arguments()).isEqualTo(new ServiceInspectArgumentsV1());
        assertThat(running.predicate()).isEqualTo(new FieldEquals("runtimeState", "RUNNING"));
        assertThat(criteria.criteria())
                .allSatisfy(criterion -> assertThat(criterion.required()).isTrue());
    }

    @Test
    void encodingRoundTripsAndCarriesTheSchemaAndDiscriminators() {
        RecoveryPolicyCriteriaV1 criteria = decode(S3_CRITERIA);

        String encoded = registry.encode(NAME, 1, criteria);

        assertThat(decode(encoded)).isEqualTo(criteria);
        JsonNode tree = JSON.readTree(encoded);
        assertThat(tree.get("schemaName").asString()).isEqualTo(NAME);
        assertThat(tree.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(tree.get("criteria").get(0).get("capabilityKey").asString()).isEqualTo("queue.inspect");
        assertThat(tree.get("criteria").get(0).get("predicate").get("type").asString())
                .isEqualTo("MONOTONIC_TREND");
        assertThat(tree.get("criteria").get(1).get("sampling").has("maxGapSeconds"))
                .isTrue();
    }

    /** 通用趋势（无健康区间）与 metrics.query 的 latest 也是合法判据。 */
    @Test
    void genericTrendsAndMetricsCriteriaAreAccepted() {
        RecoveryPolicyCriteriaV1 criteria = decode(mutate(root -> {
            ObjectNode metric = criterion(root, 0);
            metric.put("capabilityKey", "metrics.query");
            metric.put("targetResourceKey", "redirect-service");
            metric.putObject("arguments")
                    .put("metricKey", "http.request.latency.p99")
                    .put("windowKey", "LAST_15_MIN")
                    .put("comparePreviousWindow", false);
            ObjectNode predicate = metric.putObject("predicate");
            predicate.put("type", "MONOTONIC_TREND").put("field", "latest").put("direction", "INCREASING");
        }));

        assertThat(criteria.criteria().get(0).predicate())
                .isEqualTo(new MonotonicTrend("latest", TrendDirection.INCREASING, null, null));
        assertThat(criteria.criteria().get(0).field()).isEqualTo(RecoveryField.LATEST);
    }

    /** 小数阈值是合法的，按原值保存，不被取整（B23-R1）。 */
    @Test
    void decimalThresholdsKeepTheirExactValue() {
        RecoveryPolicyCriteriaV1 criteria = decode(mutate(root -> {
            predicate(root, 0).put("healthyThreshold", 12.5);
            predicate(root, 1).put("value", 20.5);
        }));

        assertThat(criteria.criteria().get(0).predicate())
                .isEqualTo(new MonotonicTrend("lag", TrendDirection.DECREASING, 12.5, true));
        assertThat(criteria.criteria().get(1).predicate())
                .isEqualTo(new NumericCompare("lag", ComparisonOperator.LTE, 20.5));
        assertThat(decode(registry.encode(NAME, 1, criteria))).isEqualTo(criteria);
    }

    @Test
    void onlyVersionOneIsRegistered() {
        assertThatThrownBy(() -> registry.decode(NAME, 2, S3_CRITERIA, RecoveryPolicyCriteriaV1.class))
                .isInstanceOfSatisfying(
                        SchemaPayloadException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.UNKNOWN_SCHEMA));
    }

    record Invalid(String description, Consumer<ObjectNode> change) {
        @Override
        public String toString() {
            return description;
        }
    }

    static Invalid[] invalidCriteria() {
        return new Invalid[] {
            new Invalid("embedded schemaVersion 2", root -> root.put("schemaVersion", 2)),
            new Invalid("embedded schemaName differs", root -> root.put("schemaName", "recovery.policy")),
            new Invalid("schemaVersion missing", root -> root.remove("schemaVersion")),
            new Invalid("unknown top-level field", root -> root.put("extra", 1)),
            new Invalid("maxDurationSeconds 0", root -> root.put("maxDurationSeconds", 0)),
            new Invalid("maxSampleAgeSeconds missing", root -> root.remove("maxSampleAgeSeconds")),
            new Invalid("sampling exceeds maxDurationSeconds", root -> root.put("maxDurationSeconds", 39)),
            new Invalid("criteria empty", root -> root.putArray("criteria")),
            new Invalid("criteria missing", root -> root.remove("criteria")),
            new Invalid("criterion null", root -> ((ArrayNode) root.get("criteria")).addNull()),
            new Invalid(
                    "duplicate criterionKey", root -> criterion(root, 1).put("criterionKey", "stream-lag-decreasing")),
            new Invalid("all optional", root -> {
                for (JsonNode node : root.get("criteria")) {
                    ((ObjectNode) node).put("required", false);
                }
            }),
            new Invalid("required missing", root -> criterion(root, 0).remove("required")),
            new Invalid("required as string", root -> criterion(root, 0).put("required", "true")),
            new Invalid("criterionKey upper case", root -> criterion(root, 0).put("criterionKey", "Stream-Lag")),
            new Invalid("criterionKey too long", root -> criterion(root, 0).put("criterionKey", "a".repeat(129))),
            new Invalid("name blank", root -> criterion(root, 0).put("name", "  ")),
            new Invalid("name too long", root -> criterion(root, 0).put("name", "名".repeat(201))),
            new Invalid("targetResourceKey invalid", root -> criterion(root, 0).put("targetResourceKey", "Stream")),
            new Invalid("unknown criterion field", root -> criterion(root, 0).put("consumerGroup", "g")),
            new Invalid(
                    "logs.search is not a recovery criterion",
                    root -> criterion(root, 3).put("capabilityKey", "logs.search")),
            new Invalid(
                    "cache.inspect has no registered projection",
                    root -> criterion(root, 3).put("capabilityKey", "cache.inspect")),
            new Invalid(
                    "service.restart is not OBSERVE",
                    root -> criterion(root, 3).put("capabilityKey", "service.restart")),
            new Invalid("capabilityKey missing", root -> criterion(root, 3).remove("capabilityKey")),
            new Invalid("arguments missing", root -> criterion(root, 0).remove("arguments")),
            new Invalid(
                    "arguments with unknown field",
                    root -> criterion(root, 0).putObject("arguments").put("stream", "x")),
            new Invalid("metrics.query arguments incomplete", root -> {
                ObjectNode metric = criterion(root, 1);
                metric.put("capabilityKey", "metrics.query");
                metric.putObject("arguments").put("metricKey", "http.request.rate");
                metric.putObject("predicate")
                        .put("type", "NUMERIC_COMPARE")
                        .put("field", "latest")
                        .put("operator", "LTE")
                        .put("value", 1);
            }),
            new Invalid(
                    "field of another capability", root -> predicate(root, 3).put("field", "lag")),
            new Invalid(
                    "JSONPath instead of a projection",
                    root -> predicate(root, 1).put("field", "consumerGroups[0].lag")),
            new Invalid("field case variant", root -> predicate(root, 1).put("field", "Lag")),
            new Invalid("FIELD_EQUALS on a numeric field", root -> {
                ObjectNode predicate = predicate(root, 1);
                predicate.removeAll();
                predicate.put("type", "FIELD_EQUALS").put("field", "lag").put("value", "0");
            }),
            new Invalid(
                    "FIELD_EQUALS value not in the result enum",
                    root -> predicate(root, 3).put("value", "running")),
            new Invalid("FIELD_EQUALS value missing", root -> predicate(root, 3).remove("value")),
            new Invalid("NUMERIC_COMPARE on a text field", root -> {
                ObjectNode predicate = predicate(root, 3);
                predicate.removeAll();
                predicate
                        .put("type", "NUMERIC_COMPARE")
                        .put("field", "runtimeState")
                        .put("operator", "EQ");
            }),
            new Invalid("unknown operator", root -> predicate(root, 1).put("operator", "LE")),
            new Invalid("threshold as string", root -> predicate(root, 1).put("value", "20")),
            new Invalid("threshold missing", root -> predicate(root, 1).remove("value")),
            new Invalid("unknown predicate type", root -> predicate(root, 1).put("type", "EXPRESSION")),
            new Invalid(
                    "expression field on predicate", root -> predicate(root, 1).put("expression", "lag < 20")),
            new Invalid("trend on a single sample", root -> {
                ObjectNode sampling = criterion(root, 0).putObject("sampling");
                sampling.put("sampleCount", 1).put("intervalSeconds", 0).putNull("maxGapSeconds");
            }),
            new Invalid(
                    "healthy threshold on an increasing trend",
                    root -> predicate(root, 0).put("direction", "INCREASING")),
            new Invalid(
                    "requireFinalHealthy without threshold",
                    root -> predicate(root, 0).remove("healthyThreshold")),
            new Invalid(
                    "threshold without requireFinalHealthy",
                    root -> predicate(root, 0).remove("requireFinalHealthy")),
            new Invalid("negative healthy threshold", root -> predicate(root, 0).put("healthyThreshold", -1)),
            new Invalid("single sample with interval", root -> sampling(root, 1).put("intervalSeconds", 5)),
            new Invalid("single sample with gap", root -> sampling(root, 1).put("maxGapSeconds", 10)),
            new Invalid(
                    "multiple samples without gap", root -> sampling(root, 2).putNull("maxGapSeconds")),
            new Invalid("gap shorter than interval", root -> sampling(root, 2).put("maxGapSeconds", 4)),
            new Invalid(
                    "multiple samples without interval",
                    root -> sampling(root, 2).put("intervalSeconds", 0)),
            new Invalid("sampleCount 0", root -> sampling(root, 1).put("sampleCount", 0)),
            new Invalid("sampling missing", root -> criterion(root, 0).remove("sampling")),
            // 整数字段不得把小数截断成整数（B23-R1）
            new Invalid("schemaVersion 1.9", root -> root.put("schemaVersion", 1.9)),
            new Invalid("schemaVersion 1.0", root -> root.put("schemaVersion", 1.0)),
            new Invalid("maxDurationSeconds 120.5", root -> root.put("maxDurationSeconds", 120.5)),
            new Invalid("maxSampleAgeSeconds 120.0", root -> root.put("maxSampleAgeSeconds", 120.0)),
            new Invalid("sampleCount 4.9", root -> sampling(root, 0).put("sampleCount", 4.9)),
            new Invalid("intervalSeconds 10.9", root -> sampling(root, 0).put("intervalSeconds", 10.9)),
            new Invalid("maxGapSeconds 20.5", root -> sampling(root, 0).put("maxGapSeconds", 20.5))
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCriteria")
    void invalidCriteriaAreRejectedBeforeActivation(Invalid invalid) {
        String payload = mutate(invalid.change());

        assertThatThrownBy(() -> decode(payload)).isInstanceOfSatisfying(SchemaPayloadException.class, ex -> {
            assertThat(ex.reason()).isEqualTo(Reason.INVALID_PAYLOAD);
            assertThat(ex.getMessage()).doesNotContain("未投递", "RUNNING");
        });
    }

    private RecoveryPolicyCriteriaV1 decode(String payload) {
        return registry.decode(NAME, 1, payload, RecoveryPolicyCriteriaV1.class);
    }

    private static String mutate(Consumer<ObjectNode> change) {
        ObjectNode root = (ObjectNode) JSON.readTree(S3_CRITERIA);
        change.accept(root);
        return JSON.writeValueAsString(root);
    }

    private static ObjectNode criterion(ObjectNode root, int index) {
        return (ObjectNode) root.get("criteria").get(index);
    }

    private static ObjectNode predicate(ObjectNode root, int index) {
        return (ObjectNode) criterion(root, index).get("predicate");
    }

    private static ObjectNode sampling(ObjectNode root, int index) {
        return (ObjectNode) criterion(root, index).get("sampling");
    }
}
