package io.github.ismoyuan.opspilot.infrastructure.schema;

import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException.Reason;
import io.github.ismoyuan.opspilot.domain.system.binding.DockerResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.LokiResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.MySqlResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.RedisResourceBindingV1;
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
 * <p>专用 JsonMapper 只用于解码受信载荷，不与 Web 共享配置，也不用于指纹（指纹统一由 CanonicalJsonWriter，07 §58）。
 */
@Component
class JacksonSchemaCodecRegistry implements SchemaCodecRegistry {

    private record SchemaKey(String name, int version) {}

    /** V0.1 只注册明确的 version=1；新增版本必须新增类型并在此登记。 */
    private static final Map<SchemaKey, Class<?>> TYPES = Map.of(
            new SchemaKey(PrometheusResourceBindingV1.SCHEMA_NAME, PrometheusResourceBindingV1.SCHEMA_VERSION),
            PrometheusResourceBindingV1.class,
            new SchemaKey(LokiResourceBindingV1.SCHEMA_NAME, LokiResourceBindingV1.SCHEMA_VERSION),
            LokiResourceBindingV1.class,
            new SchemaKey(RedisResourceBindingV1.SCHEMA_NAME, RedisResourceBindingV1.SCHEMA_VERSION),
            RedisResourceBindingV1.class,
            new SchemaKey(MySqlResourceBindingV1.SCHEMA_NAME, MySqlResourceBindingV1.SCHEMA_VERSION),
            MySqlResourceBindingV1.class,
            new SchemaKey(DockerResourceBindingV1.SCHEMA_NAME, DockerResourceBindingV1.SCHEMA_VERSION),
            DockerResourceBindingV1.class);

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
            .build();

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
