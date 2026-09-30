package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.HealthStatus;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 恢复谓词可选择的注册标量投影（06 §113、07 §97）：只列出规格点名的字段，不支持 JSONPath、表达式或默认取数组第一项。
 * queue.inspect 的 lag/pendingCount 取 Binding 指定的 consumerGroup，组缺失或字段为空即 UNKNOWN（求值属 TASK-077）。
 *
 * <p>V0.1 的 cache.inspect、database.inspect 没有注册投影，因而不能作为恢复判据（S1/S2 无恢复策略，09 §75 只定义 S3）；
 * logs.search 不作为恢复判据（06 §115）。
 */
public enum RecoveryField {
    RUNTIME_STATE(CapabilityKey.SERVICE_INSPECT, "runtimeState", names(RuntimeState.values())),
    HEALTH_STATUS(CapabilityKey.SERVICE_INSPECT, "healthStatus", names(HealthStatus.values())),
    LAG(CapabilityKey.QUEUE_INSPECT, "lag", Set.of()),
    PENDING_COUNT(CapabilityKey.QUEUE_INSPECT, "pendingCount", Set.of()),
    LATEST(CapabilityKey.METRICS_QUERY, "latest", Set.of());

    private final CapabilityKey capability;
    private final String fieldName;
    private final Set<String> textValues;

    RecoveryField(CapabilityKey capability, String fieldName, Set<String> textValues) {
        this.capability = capability;
        this.fieldName = fieldName;
        this.textValues = textValues;
    }

    public CapabilityKey capability() {
        return capability;
    }

    public String fieldName() {
        return fieldName;
    }

    /** 数值字段可用于 NUMERIC_COMPARE 与 MONOTONIC_TREND；文本（枚举）字段只可用于 FIELD_EQUALS。 */
    public boolean numeric() {
        return textValues.isEmpty();
    }

    /** 文本字段允许比较的取值（结果 Schema 的枚举名）；数值字段为空集。 */
    public Set<String> textValues() {
        return textValues;
    }

    /** 精确匹配该能力下注册的字段名；大小写变体或其他能力的字段不是投影。 */
    public static Optional<RecoveryField> find(CapabilityKey capability, String fieldName) {
        Objects.requireNonNull(capability, "capability");
        return Arrays.stream(values())
                .filter(field -> field.capability == capability && field.fieldName.equals(fieldName))
                .findFirst();
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
