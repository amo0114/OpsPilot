package io.github.ismoyuan.opspilot.application.schema;

import io.github.ismoyuan.opspilot.domain.system.ResourceBinding;

/**
 * 按 schemaName/schemaVersion 在 JSON 载荷与已注册的强类型对象之间编解码（04 §69、07 §96）。
 *
 * <p>只接受明确注册的版本，未知版本拒绝，不做兼容猜测；应用层不以 Map 或 JsonNode 传递载荷。
 *
 * <p>与 CanonicalJsonWriter（07 §58）分工：本注册表产生按 Schema 保存的结果与 Observation 载荷（显式写出 null 字段，如 lag 未知）；
 * 指纹与 request_payload 只由 CanonicalJsonWriter 产生。
 */
public interface SchemaCodecRegistry {

    /**
     * @param type 调用方期望的类型，必须与该 schema 注册的类型一致
     * @throws SchemaPayloadException schema 未注册、类型不符或载荷不合法
     */
    <T> T decode(String schemaName, int schemaVersion, String payload, Class<T> type);

    /**
     * @param value 该 schema 注册类型的实例
     * @throws SchemaPayloadException schema 未注册或 value 类型不符
     */
    String encode(String schemaName, int schemaVersion, Object value);

    default <T> T decodeSelector(ResourceBinding binding, Class<T> type) {
        return decode(
                binding.selectorSchema().name(), binding.selectorSchema().version(), binding.selectorPayload(), type);
    }
}
