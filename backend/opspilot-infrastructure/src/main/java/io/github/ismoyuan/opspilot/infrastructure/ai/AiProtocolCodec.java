package io.github.ismoyuan.opspilot.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

/**
 * contracts/ai-runtime/v1 的严格 JSON 编解码（07 §80～§84）。与 JSON Schema 对齐：拒绝未知字段、重复键、多余尾随内容、
 * 标量隐式转换（"2" → 2、2.0 → 2、1 → "1"、"true" → true）；时间固定为 UTC 毫秒格式；空的可选字段不输出。
 * 结构与取值校验在协议 record 的构造器中。解码失败统一为 AI_OUTPUT_INVALID，信息只含消息类型，不回显原文（05 §93）。
 */
public final class AiProtocolCodec {

    private static final Pattern TIMESTAMP =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .withCoercionConfig(
                    LogicalType.Textual,
                    config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .changeDefaultPropertyInclusion(
                    value -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .addModule(new SimpleModule("ai-protocol-v1")
                    .addSerializer(Instant.class, new TimestampSerializer())
                    .addDeserializer(Instant.class, new TimestampDeserializer()))
            .build();

    /**
     * @throws ApplicationException AI_OUTPUT_INVALID：不是合法的 {@code type} 协议消息
     */
    public <T> T decode(String json, Class<T> type) {
        T message;
        try {
            message = mapper.readValue(json, type);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw rejected(type, ex);
        }
        // 可选字段可以为 null，整个消息不行：顶层 null 在此与其他违规一样拒绝
        if (message == null) {
            throw rejected(type, null);
        }
        return message;
    }

    private static ApplicationException rejected(Class<?> type, Throwable cause) {
        return new ApplicationException(
                ErrorCode.AI_OUTPUT_INVALID,
                "AI protocol message rejected: " + type.getSimpleName(),
                Map.of("message", type.getSimpleName()),
                cause);
    }

    public String encode(Object message) {
        return mapper.writeValueAsString(message);
    }

    private static final class TimestampSerializer extends ValueSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(FORMAT.format(value));
        }
    }

    /** 只接受 yyyy-MM-ddTHH:mm:ss.SSSZ 且日历上存在的时间。 */
    private static final class TimestampDeserializer extends ValueDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (Instant) context.handleUnexpectedToken(Instant.class, parser);
            }
            String text = parser.getString();
            if (!TIMESTAMP.matcher(text).matches()) {
                throw new IllegalArgumentException("invalid protocol timestamp");
            }
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ex) {
                // 形如 2026-02-30 的日历上不存在的时间
                throw new IllegalArgumentException("invalid protocol timestamp", ex);
            }
        }
    }
}
