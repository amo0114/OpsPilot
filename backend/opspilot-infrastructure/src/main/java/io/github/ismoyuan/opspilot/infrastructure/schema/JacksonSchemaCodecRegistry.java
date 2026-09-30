package io.github.ismoyuan.opspilot.infrastructure.schema;

import io.github.ismoyuan.opspilot.application.capability.extract.CacheStatusObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.DatabaseStatusObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.LogPatternObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.MetricObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.QueueStatusObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.ServiceStatusObservationV1;
import io.github.ismoyuan.opspilot.application.capability.result.CacheInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutionContextV1;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartResultV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyCriteriaV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySnapshotV1;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException.Reason;
import io.github.ismoyuan.opspilot.domain.system.binding.DockerResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.LokiResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.MySqlResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.RedisResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.connection.DockerConnectionConfigV1;
import io.github.ismoyuan.opspilot.domain.system.connection.HttpConnectionConfigV1;
import io.github.ismoyuan.opspilot.domain.system.connection.MySqlConnectionConfigV1;
import io.github.ismoyuan.opspilot.domain.system.connection.RedisConnectionConfigV1;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * 以严格 Jackson 配置把载荷解码为注册的 record；业务不变量由 record 构造器校验。
 *
 * <p>专用 JsonMapper 只用于受信载荷的编解码，不与 Web 共享配置，也不用于指纹（指纹统一由 CanonicalJsonWriter，07 §58）。
 * 编码显式写出 null 字段，时间为 ISO-8601 文本，与严格解码往返一致。
 */
@Component
class JacksonSchemaCodecRegistry implements SchemaCodecRegistry {

    private record SchemaKey(String name, int version) {}

    /** V0.1 只注册明确的 version=1；新增版本必须新增类型并在此登记。 */
    private static final Map<SchemaKey, Class<?>> TYPES = Map.ofEntries(
            entry(
                    PrometheusResourceBindingV1.SCHEMA_NAME,
                    PrometheusResourceBindingV1.SCHEMA_VERSION,
                    PrometheusResourceBindingV1.class),
            entry(LokiResourceBindingV1.SCHEMA_NAME, LokiResourceBindingV1.SCHEMA_VERSION, LokiResourceBindingV1.class),
            entry(
                    RedisResourceBindingV1.SCHEMA_NAME,
                    RedisResourceBindingV1.SCHEMA_VERSION,
                    RedisResourceBindingV1.class),
            entry(
                    MySqlResourceBindingV1.SCHEMA_NAME,
                    MySqlResourceBindingV1.SCHEMA_VERSION,
                    MySqlResourceBindingV1.class),
            entry(
                    DockerResourceBindingV1.SCHEMA_NAME,
                    DockerResourceBindingV1.SCHEMA_VERSION,
                    DockerResourceBindingV1.class),
            // HTTP 数据源连接配置（B16-R1：认证方式；凭据只经 credentialRef）
            entry(
                    HttpConnectionConfigV1.PROMETHEUS_SCHEMA_NAME,
                    HttpConnectionConfigV1.SCHEMA_VERSION,
                    HttpConnectionConfigV1.class),
            entry(
                    HttpConnectionConfigV1.LOKI_SCHEMA_NAME,
                    HttpConnectionConfigV1.SCHEMA_VERSION,
                    HttpConnectionConfigV1.class),
            entry(
                    RedisConnectionConfigV1.SCHEMA_NAME,
                    RedisConnectionConfigV1.SCHEMA_VERSION,
                    RedisConnectionConfigV1.class),
            entry(
                    MySqlConnectionConfigV1.SCHEMA_NAME,
                    MySqlConnectionConfigV1.SCHEMA_VERSION,
                    MySqlConnectionConfigV1.class),
            entry(
                    DockerConnectionConfigV1.SCHEMA_NAME,
                    DockerConnectionConfigV1.SCHEMA_VERSION,
                    DockerConnectionConfigV1.class),
            // Capability 结果（06 §118～§119）
            entry(MetricsQueryResultV1.SCHEMA_NAME, MetricsQueryResultV1.SCHEMA_VERSION, MetricsQueryResultV1.class),
            entry(LogsSearchResultV1.SCHEMA_NAME, LogsSearchResultV1.SCHEMA_VERSION, LogsSearchResultV1.class),
            entry(CacheInspectResultV1.SCHEMA_NAME, CacheInspectResultV1.SCHEMA_VERSION, CacheInspectResultV1.class),
            entry(
                    DatabaseInspectResultV1.SCHEMA_NAME,
                    DatabaseInspectResultV1.SCHEMA_VERSION,
                    DatabaseInspectResultV1.class),
            entry(QueueInspectResultV1.SCHEMA_NAME, QueueInspectResultV1.SCHEMA_VERSION, QueueInspectResultV1.class),
            entry(
                    ServiceInspectResultV1.SCHEMA_NAME,
                    ServiceInspectResultV1.SCHEMA_VERSION,
                    ServiceInspectResultV1.class),
            // Observation 载荷（06 §117）
            entry(MetricObservationV1.SCHEMA_NAME, MetricObservationV1.SCHEMA_VERSION, MetricObservationV1.class),
            entry(
                    LogPatternObservationV1.SCHEMA_NAME,
                    LogPatternObservationV1.SCHEMA_VERSION,
                    LogPatternObservationV1.class),
            entry(
                    CacheStatusObservationV1.SCHEMA_NAME,
                    CacheStatusObservationV1.SCHEMA_VERSION,
                    CacheStatusObservationV1.class),
            entry(
                    DatabaseStatusObservationV1.SCHEMA_NAME,
                    DatabaseStatusObservationV1.SCHEMA_VERSION,
                    DatabaseStatusObservationV1.class),
            entry(
                    QueueStatusObservationV1.SCHEMA_NAME,
                    QueueStatusObservationV1.SCHEMA_VERSION,
                    QueueStatusObservationV1.class),
            entry(
                    ServiceStatusObservationV1.SCHEMA_NAME,
                    ServiceStatusObservationV1.SCHEMA_VERSION,
                    ServiceStatusObservationV1.class),
            // 恢复策略 Criteria（06 §113、07 §97）：激活前完整校验，快照按同一类型解释
            entry(
                    RecoveryPolicyCriteriaV1.SCHEMA_NAME,
                    RecoveryPolicyCriteriaV1.SCHEMA_VERSION,
                    RecoveryPolicyCriteriaV1.class),
            // Execution 创建时冻结的恢复合同与受信执行上下文（04 §45、§52）
            entry(
                    RecoveryPolicySnapshotV1.SCHEMA_NAME,
                    RecoveryPolicySnapshotV1.SCHEMA_VERSION,
                    RecoveryPolicySnapshotV1.class),
            entry(
                    ServiceRestartExecutionContextV1.SCHEMA_NAME,
                    ServiceRestartExecutionContextV1.SCHEMA_VERSION,
                    ServiceRestartExecutionContextV1.class),
            entry(
                    ServiceRestartResultV1.SCHEMA_NAME,
                    ServiceRestartResultV1.SCHEMA_VERSION,
                    ServiceRestartResultV1.class));

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // 数字、布尔不得被悄悄转成字符串字段或 Map 值
            .withCoercionConfig(
                    LogicalType.Textual,
                    config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            // 小数不得被截断成整数字段（版本号、计数、秒数）；整数写入小数字段（阈值）仍是精确的，保持允许（B23-R1）
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(
                    LogicalType.Integer, config -> config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            .build();

    @Override
    public String encode(String schemaName, int schemaVersion, Object value) {
        Class<?> registered = TYPES.get(new SchemaKey(schemaName, schemaVersion));
        if (registered == null) {
            throw new SchemaPayloadException(Reason.UNKNOWN_SCHEMA, schemaName, schemaVersion, null);
        }
        if (value == null || !registered.equals(value.getClass())) {
            throw new SchemaPayloadException(
                    Reason.TYPE_MISMATCH, schemaName, schemaVersion, "expected " + registered.getSimpleName());
        }
        return mapper.writeValueAsString(value);
    }

    @Override
    public <T> T decode(String schemaName, int schemaVersion, String payload, Class<T> type) {
        Class<?> registered = TYPES.get(new SchemaKey(schemaName, schemaVersion));
        if (registered == null) {
            throw new SchemaPayloadException(Reason.UNKNOWN_SCHEMA, schemaName, schemaVersion, null);
        }
        if (!registered.equals(type)) {
            throw new SchemaPayloadException(
                    Reason.TYPE_MISMATCH, schemaName, schemaVersion, "expected " + type.getSimpleName());
        }
        if (payload == null) {
            throw new SchemaPayloadException(Reason.INVALID_PAYLOAD, schemaName, schemaVersion, "payload is null");
        }
        T value;
        try {
            value = mapper.readValue(payload, type);
        } catch (JacksonException ex) {
            throw new SchemaPayloadException(Reason.INVALID_PAYLOAD, schemaName, schemaVersion, describe(ex));
        }
        if (value == null) {
            throw new SchemaPayloadException(Reason.INVALID_PAYLOAD, schemaName, schemaVersion, "payload is null");
        }
        return value;
    }

    /**
     * 只取异常类型、字段路径与 record 校验文本；Jackson 自身 message 可能引用载荷值，不使用。
     * record 构造器的校验文本约定只含字段名。
     */
    private static Map.Entry<SchemaKey, Class<?>> entry(String name, int version, Class<?> type) {
        return Map.entry(new SchemaKey(name, version), type);
    }

    private static String describe(JacksonException ex) {
        StringJoiner path = new StringJoiner(".");
        for (JacksonException.Reference reference : ex.getPath()) {
            path.add(
                    reference.getPropertyName() != null
                            ? reference.getPropertyName()
                            : "[" + reference.getIndex() + "]");
        }
        String detail = ex.getClass().getSimpleName() + " at '" + path + "'";
        Throwable cause = ex.getCause();
        if (cause instanceof IllegalArgumentException || cause instanceof NullPointerException) {
            detail += ": " + cause.getMessage();
        }
        return detail;
    }
}
