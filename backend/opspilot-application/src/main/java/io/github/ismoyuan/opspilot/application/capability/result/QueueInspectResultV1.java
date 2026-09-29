package io.github.ismoyuan.opspilot.application.capability.result;

import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * queue.inspect.result / 1（06 §86）：Redis Stream 长度与 Binding 指定消费组的统计，不读取消息正文（CAP-INV-009）。
 * 单次调用只是一个时点，不含趋势（06 §88）。
 *
 * @param streamLength 保留的 Entry 数，不是积压
 */
public record QueueInspectResultV1(
        QueueType queueType,
        long streamLength,
        String lastGeneratedId,
        Instant lastGeneratedAt,
        List<ConsumerGroup> consumerGroups)
        implements CapabilityResult {

    public static final String SCHEMA_NAME = "queue.inspect.result";
    public static final int SCHEMA_VERSION = 1;

    public enum QueueType {
        REDIS_STREAM
    }

    /**
     * @param consumerCount 组内登记的消费者数，不等于真实运行进程数
     * @param pendingCount 已投递未 ACK
     * @param lag 尚未投递的数量；无法可靠取得时为空，不估算为 0
     * @param lastDeliveredAt 最近一次投递时间，不是 ACK 时间
     */
    public record ConsumerGroup(
            String group,
            long consumerCount,
            long pendingCount,
            Long lag,
            String lastDeliveredId,
            Instant lastDeliveredAt) {

        public ConsumerGroup {
            ResultChecks.text("group", group);
            ResultChecks.nonNegative("consumerCount", consumerCount);
            ResultChecks.nonNegative("pendingCount", pendingCount);
            ResultChecks.optionalNonNegative("lag", lag);
            ResultChecks.optionalText("lastDeliveredId", lastDeliveredId);
        }
    }

    public QueueInspectResultV1 {
        Objects.requireNonNull(queueType, "queueType");
        ResultChecks.nonNegative("streamLength", streamLength);
        ResultChecks.optionalText("lastGeneratedId", lastGeneratedId);
        consumerGroups = ResultChecks.list("consumerGroups", consumerGroups);
    }

    @Override
    public CapabilitySchema resultSchema() {
        return new CapabilitySchema(SCHEMA_NAME, SCHEMA_VERSION);
    }
}
