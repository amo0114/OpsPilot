package io.github.ismoyuan.opspilot.domain.system.binding;

import java.util.regex.Pattern;

/**
 * 资源在 Redis 中的定位（06 §19、§84）。缓存资源（cache.inspect）无需选择器，两项均为空；
 * Redis Stream 资源（queue.inspect）必须同时给出 streamKey 与 consumerGroup，二者由配置提供，不由 AI 生成。
 *
 * @param streamKey 可为空；非空时为精确键名
 * @param consumerGroup 可为空；与 streamKey 同时出现
 */
public record RedisResourceBindingV1(String streamKey, String consumerGroup) implements ResourceSelector {

    public static final String SCHEMA_NAME = "redis.resource.binding";
    public static final int SCHEMA_VERSION = 1;

    /** 可见 ASCII 且不含空白，也不含 glob 元字符，避免被当作匹配模式。 */
    private static final Pattern NAME = Pattern.compile("[!-~&&[^*?\\[\\]]]{1,256}");

    public RedisResourceBindingV1 {
        if ((streamKey == null) != (consumerGroup == null)) {
            throw new IllegalArgumentException("streamKey and consumerGroup must be both present or both absent");
        }
        if (streamKey != null && !NAME.matcher(streamKey).matches()) {
            throw new IllegalArgumentException("streamKey is invalid");
        }
        if (consumerGroup != null && !NAME.matcher(consumerGroup).matches()) {
            throw new IllegalArgumentException("consumerGroup is invalid");
        }
    }

    public boolean hasStream() {
        return streamKey != null;
    }
}
