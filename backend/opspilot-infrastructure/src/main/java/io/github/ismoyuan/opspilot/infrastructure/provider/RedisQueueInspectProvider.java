package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.provider.ObserveProvider;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1.ConsumerGroup;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1.QueueType;
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
 * queue.inspect 的 Redis Stream Provider（08 TASK-056、06 §82～§91、09 §10～§12）。Stream 键与消费组只来自 Binding，AI 不提供；只发送
 * XINFO STREAM / GROUPS / CONSUMERS（{@link RedisCommand}），不把消息正文用于任何结果（CAP-INV-009）：XINFO STREAM 应答中附带的首末条目
 * 会随应答传输并被通用 RESP 解析器解码到内存，在组装结果前即移除，不进入结果、原始结果、Observation 或日志。
 *
 * <p>语义（不估算、不改名）：streamLength 为保留条目数，不是积压；lag 为该组尚未投递的条目数，Redis 无法给出时为空（不当 0）；
 * pendingCount 为已投递未 ACK；lastGeneratedAt 取最近生成 ID 的毫秒部分（自动 ID 即生成时间）；lastDeliveredAt 为组内消费者最近一次
 * 成功读取的时间（XINFO CONSUMERS 的 inactive，Redis ≥ 7.2），不是条目 ID 的时间，也不是 ACK 时间，不可得为空。只返回 Binding 指定的组，
 * 组不存在时组列表为空；Stream 不存在为 RESOURCE_NOT_FOUND。原始结果只含上述统计，逐行“字段 值”。
 */
final class RedisQueueInspectProvider implements ObserveProvider {

    private final RedisAccess redis;
    private final Clock clock;

    RedisQueueInspectProvider(RedisAccess redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public CapabilityKey capability() {
        return CapabilityKey.QUEUE_INSPECT;
    }

    @Override
    public ProviderOutcome fetch(AdmittedInvocation invocation, Instant deadline) {
        try {
            if (!(invocation.provider().selector() instanceof RedisResourceBindingV1 selector)
                    || !selector.hasStream()) {
                throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Resource binding has no Redis stream");
            }
            try (RedisConnection connection =
                    redis.connect(invocation.provider().connection(), deadline)) {
                Map<String, Object> stream = stream(connection, selector.streamKey(), deadline);
                long length = number(stream.get("length"));
                String lastGeneratedId = entryId(stream.get("last-generated-id"));
                ConsumerGroup group = group(connection, selector, deadline);
                Instant observedAt = clock.instant();
                QueueInspectResultV1 result = new QueueInspectResultV1(
                        QueueType.REDIS_STREAM,
                        length,
                        lastGeneratedId,
                        idTime(lastGeneratedId),
                        group == null ? List.of() : List.of(group));
                return new ProviderOutcome.Fetched(result, rawLines(result), observedAt);
            } catch (RedisErrorReply reply) {
                throw RedisAccess.translate(reply);
            }
        } catch (ProviderCallException ex) {
            return ex.outcome();
        }
    }

    /** XINFO STREAM 的统计字段；first-entry、last-entry（含消息正文）在此移除，其内容不被使用。 */
    private static Map<String, Object> stream(RedisConnection connection, String key, Instant deadline) {
        Object reply;
        try {
            reply = connection.call(RedisCommand.XINFO_STREAM, deadline, key);
        } catch (RedisErrorReply error) {
            if (error.code().equals("ERR") && error.mentions("no such key")) {
                throw new ProviderCallException(ErrorCode.RESOURCE_NOT_FOUND, "Redis stream does not exist");
            }
            throw error;
        }
        Map<String, Object> fields = pairs(reply);
        fields.remove("first-entry");
        fields.remove("last-entry");
        return fields;
    }

    private ConsumerGroup group(RedisConnection connection, RedisResourceBindingV1 selector, Instant deadline) {
        if (!(connection.call(RedisCommand.XINFO_GROUPS, deadline, selector.streamKey()) instanceof List<?> groups)) {
            throw invalid();
        }
        for (Object entry : groups) {
            Map<String, Object> group = pairs(entry);
            if (!selector.consumerGroup().equals(group.get("name"))) {
                continue;
            }
            Object lag = group.get("lag");
            String lastDeliveredId = entryId(group.get("last-delivered-id"));
            return new ConsumerGroup(
                    selector.consumerGroup(),
                    number(group.get("consumers")),
                    number(group.get("pending")),
                    lag == null ? null : number(lag),
                    lastDeliveredId,
                    lastDelivery(connection, selector, deadline));
        }
        return null;
    }

    /**
     * 组内消费者最近一次成功读取距今最短的时间；inactive 为 -1（从未成功读取）的消费者不参与，全部从未读取、没有消费者或 Redis 不提供
     * inactive 时为空。
     */
    private Instant lastDelivery(RedisConnection connection, RedisResourceBindingV1 selector, Instant deadline) {
        Object reply;
        try {
            reply = connection.call(
                    RedisCommand.XINFO_CONSUMERS, deadline, selector.streamKey(), selector.consumerGroup());
        } catch (RedisErrorReply error) {
            if (error.code().equals("NOGROUP")) {
                return null;
            }
            throw error;
        }
        Instant now = clock.instant();
        if (!(reply instanceof List<?> consumers)) {
            throw invalid();
        }
        Long shortest = null;
        for (Object entry : consumers) {
            Object inactive = pairs(entry).get("inactive");
            if (inactive == null) {
                continue;
            }
            if (!(inactive instanceof Long millis)) {
                throw invalid();
            }
            // -1：该消费者从未成功读取（合法值），不参与投递时间，也不影响其他统计（B17-R1）
            if (millis >= 0 && (shortest == null || millis < shortest)) {
                shortest = millis;
            }
        }
        return shortest == null ? null : now.minusMillis(shortest);
    }

    /** RESP2 的扁平键值数组 → Map；值可为任意应答。 */
    private static Map<String, Object> pairs(Object reply) {
        if (!(reply instanceof List<?> items) || items.size() % 2 != 0) {
            throw invalid();
        }
        Map<String, Object> fields = new HashMap<>();
        for (int i = 0; i < items.size(); i += 2) {
            if (!(items.get(i) instanceof String name)) {
                throw invalid();
            }
            fields.put(name, items.get(i + 1));
        }
        return fields;
    }

    private static long number(Object value) {
        if (value instanceof Long number && number >= 0) {
            return number;
        }
        throw invalid();
    }

    /** 0-0 表示从未生成或投递，按“无”处理。 */
    private static String entryId(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String id) || !id.matches("\\d+-\\d+")) {
            throw invalid();
        }
        return id.equals("0-0") ? null : id;
    }

    private static Instant idTime(String id) {
        return id == null ? null : Instant.ofEpochMilli(Long.parseLong(id.substring(0, id.indexOf('-'))));
    }

    private static String rawLines(QueueInspectResultV1 result) {
        StringBuilder raw = new StringBuilder()
                .append("stream_length ")
                .append(result.streamLength())
                .append('\n')
                .append("last_generated_id ")
                .append(result.lastGeneratedId())
                .append('\n');
        for (ConsumerGroup group : result.consumerGroups()) {
            raw.append("group ")
                    .append(group.group())
                    .append('\n')
                    .append("consumers ")
                    .append(group.consumerCount())
                    .append('\n')
                    .append("pending ")
                    .append(group.pendingCount())
                    .append('\n')
                    .append("lag ")
                    .append(group.lag())
                    .append('\n')
                    .append("last_delivered_id ")
                    .append(group.lastDeliveredId())
                    .append('\n')
                    .append("last_delivered_at ")
                    .append(group.lastDeliveredAt())
                    .append('\n');
        }
        return raw.toString();
    }

    private static ProviderCallException invalid() {
        return new ProviderCallException(ErrorCode.PROVIDER_RESPONSE_INVALID, "Redis stream statistics are invalid");
    }
}
