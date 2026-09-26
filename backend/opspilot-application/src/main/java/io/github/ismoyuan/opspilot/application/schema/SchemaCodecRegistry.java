package io.github.ismoyuan.opspilot.application.schema;

import io.github.ismoyuan.opspilot.domain.system.ResourceBinding;

/**
 * 按 schemaName/schemaVersion 把 JSON 载荷解码为已注册的强类型对象（04 §69、07 §96）。
 *
 * <p>只接受明确注册的版本，未知版本拒绝，不做兼容猜测；应用层不以 Map 或 JsonNode 传递载荷。
 */
public interface SchemaCodecRegistry {

    /**
     * @param type 调用方期望的类型，必须与该 schema 注册的类型一致
     * @throws SchemaPayloadException schema 未注册、类型不符或载荷不合法
     */
    <T> T decode(String schemaName, int schemaVersion, String payload, Class<T> type);

    default <T> T decodeSelector(ResourceBinding binding, Class<T> type) {
        return decode(
                binding.selectorSchema().name(), binding.selectorSchema().version(), binding.selectorPayload(), type);
    }
}
