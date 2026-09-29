package io.github.ismoyuan.opspilot.application.capability.extract;

import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1.ConsumerGroup;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1.QueueType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** queue-status.observation / 1（06 §89、§117）：本次采样的 Stream 长度与所选组的 lag、pendingCount；单个时点，不含趋势。 */
public record QueueStatusObservationV1(
        QueueType queueType,
        long streamLength,
        String lastGeneratedId,
        Instant lastGeneratedAt,
        List<ConsumerGroup> consumerGroups) {

    public static final String SCHEMA_NAME = "queue-status.observation";
    public static final int SCHEMA_VERSION = 1;

    public QueueStatusObservationV1 {
        Objects.requireNonNull(queueType, "queueType");
        consumerGroups = List.copyOf(Objects.requireNonNull(consumerGroups, "consumerGroups"));
    }
}
