package io.github.ismoyuan.opspilot.infrastructure.provider;

import java.util.List;

/**
 * OpsPilot 可以向 Redis 发送的全部命令（06 §60、§63、§87，08 TASK-054/056）：认证、PING、INFO 与 Stream 的只读统计。没有 GET、KEYS、
 * SCAN、HGETALL、SET、DEL、FLUSHDB、CONFIG、XRANGE 等——客户端只能发送本枚举中的命令，白名单由类型保证；Redis ACL 仍是最终防线。
 */
enum RedisCommand {
    AUTH("AUTH"),
    PING("PING"),
    INFO("INFO"),
    XINFO_STREAM("XINFO", "STREAM"),
    XINFO_GROUPS("XINFO", "GROUPS"),
    XINFO_CONSUMERS("XINFO", "CONSUMERS");

    private final List<String> words;

    RedisCommand(String... words) {
        this.words = List.of(words);
    }

    List<String> words() {
        return words;
    }
}
