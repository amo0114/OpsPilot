package io.github.ismoyuan.opspilot.application.canonical;

/**
 * 全系统唯一的规范 JSON 写出器（07 §58、08 TASK-047）：对象字段与 Map Key 按字典序稳定排序，值为 null 的字段省略（与 AI 协议
 * “可选＝缺省或 null”一致），空对象为 {}，数组保持原顺序，不含空白。Capability 调用指纹与 request_payload 都由它产生；
 * 各模块不得自建 ObjectMapper 另算一套。
 */
public interface CanonicalJsonWriter {

    /** 把强类型值（如 Capability 参数记录）写为规范 JSON。 */
    String write(Object value);

    /**
     * 把 JSON 对象或数组文本转为同一规范形式，用于与已持久化的载荷比较（数据库会改变键序与空白）。
     *
     * @throws IllegalArgumentException 文本不是合法 JSON
     */
    String canonicalize(String json);
}
