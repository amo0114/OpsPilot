package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.provider.ObserveProvider;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.CacheInspectResultV1;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.binding.RedisResourceBindingV1;
import io.github.ismoyuan.opspilot.infrastructure.provider.RedisConnection.RedisErrorReply;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * cache.inspect 的 Redis Provider（08 TASK-054、06 §58～§67）：只发送 PING 与 INFO（{@link RedisCommand}），不读取任何业务 Key 或 Value
 * （CAP-INV-008）。PING 往返时间即 pingLatencyMs（经 Toxiproxy 时反映真实网络延迟，09 §9）；INFO 只取白名单字段，缺失或无法解析为空，
 * maxmemory 为 0（未设置）时为空；hitRate 由结果类型按命中/未命中派生。能连接并得到应答才是成功，连接失败为调用失败（06 §33）。
 * 原始结果只含白名单字段，逐行“字段:值”。
 */
final class RedisCacheInspectProvider implements ObserveProvider {

    static final List<String> INFO_FIELDS = List.of(
            "used_memory",
            "maxmemory",
            "connected_clients",
            "blocked_clients",
            "instantaneous_ops_per_sec",
            "keyspace_hits",
            "keyspace_misses",
            "evicted_keys",
            "expired_keys",
            "uptime_in_seconds");

    private final RedisAccess redis;
    private final Clock clock;

    RedisCacheInspectProvider(RedisAccess redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public CapabilityKey capability() {
        return CapabilityKey.CACHE_INSPECT;
    }

    @Override
    public ProviderOutcome fetch(AdmittedInvocation invocation, Instant deadline) {
        try {
            if (!(invocation.provider().selector() instanceof RedisResourceBindingV1)) {
                throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Resource binding is not a Redis binding");
            }
            try (RedisConnection connection =
                    redis.connect(invocation.provider().connection(), deadline)) {
                long started = System.nanoTime();
                Object pong = connection.call(RedisCommand.PING, deadline);
                long pingLatencyMs = Math.round((System.nanoTime() - started) / 1_000_000.0);
                if (!"PONG".equals(pong)) {
                    throw new ProviderCallException(ErrorCode.PROVIDER_RESPONSE_INVALID, "Redis did not answer PING");
                }
                if (!(connection.call(RedisCommand.INFO, deadline) instanceof String info)) {
                    throw new ProviderCallException(ErrorCode.PROVIDER_RESPONSE_INVALID, "Redis INFO reply is invalid");
                }
                Instant observedAt = clock.instant();
                Map<String, Long> fields = parseInfo(info);
                Long maxMemory = fields.get("maxmemory");
                return new ProviderOutcome.Fetched(
                        new CacheInspectResultV1(
                                true,
                                pingLatencyMs,
                                fields.get("used_memory"),
                                maxMemory == null || maxMemory == 0 ? null : maxMemory,
                                fields.get("connected_clients"),
                                fields.get("blocked_clients"),
                                fields.get("instantaneous_ops_per_sec"),
                                fields.get("keyspace_hits"),
                                fields.get("keyspace_misses"),
                                null,
                                fields.get("evicted_keys"),
                                fields.get("expired_keys"),
                                fields.get("uptime_in_seconds")),
                        rawLines(pingLatencyMs, fields),
                        observedAt);
            } catch (RedisErrorReply reply) {
                throw RedisAccess.translate(reply);
            }
        } catch (ProviderCallException ex) {
            return ex.outcome();
        }
    }

    /** INFO 文本中白名单字段的非负整数值；其他字段（路径、配置、键空间明细等）不读取。 */
    static Map<String, Long> parseInfo(String info) {
        Map<String, Long> fields = new HashMap<>();
        for (String line : info.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0 || line.startsWith("#")) {
                continue;
            }
            String name = line.substring(0, colon);
            if (!INFO_FIELDS.contains(name)) {
                continue;
            }
            try {
                long value = Long.parseLong(line.substring(colon + 1).strip());
                if (value >= 0) {
                    fields.put(name, value);
                }
            } catch (NumberFormatException ignored) {
                // 无法解析即视为未取得
            }
        }
        return fields;
    }

    private static String rawLines(long pingLatencyMs, Map<String, Long> fields) {
        StringBuilder raw =
                new StringBuilder("ping_latency_ms:").append(pingLatencyMs).append('\n');
        INFO_FIELDS.forEach(name -> {
            if (fields.containsKey(name)) {
                raw.append(name).append(':').append(fields.get(name)).append('\n');
            }
        });
        return raw.toString();
    }
}
