package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.capability.result.CapabilityResult;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.HealthStatus;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import java.util.Optional;

/**
 * 注册标量投影的唯一实现（06 §113、08 TASK-077）：按 {@link RecoveryField} 从类型化结果取值，不执行 JSONPath、表达式或反射。
 * queue.inspect 只取快照冻结的 consumerGroup，组缺失、lag 为空即 UNKNOWN；service.inspect 的 UNKNOWN 状态是“不知道”，不是某个
 * 可比较的取值；metrics.query 的 latest 为空（窗口内无数据）即 UNKNOWN。
 */
public final class RecoveryProjection {

    private RecoveryProjection() {}

    /** 该能力成功结果的类型化 Schema 类（与 Codec 注册一致）。 */
    public static Class<? extends CapabilityResult> resultType(CapabilityKey capability) {
        return switch (capability) {
            case SERVICE_INSPECT -> ServiceInspectResultV1.class;
            case QUEUE_INSPECT -> QueueInspectResultV1.class;
            case METRICS_QUERY -> MetricsQueryResultV1.class;
            default -> throw new IllegalArgumentException("no recovery projection for " + capability.key());
        };
    }

    /**
     * @param consumerGroup queue.inspect 项为快照冻结的组，其余为空
     */
    public static ProjectedValue project(CapabilityResult result, RecoveryField field, String consumerGroup) {
        return switch (field) {
            case RUNTIME_STATE ->
                result instanceof ServiceInspectResultV1 service
                        ? service.runtimeState() == RuntimeState.UNKNOWN
                                ? new ProjectedValue.Unknown(ProjectedValue.Unknown.STATE_UNKNOWN)
                                : new ProjectedValue.Text(service.runtimeState().name())
                        : mismatch();
            case HEALTH_STATUS ->
                result instanceof ServiceInspectResultV1 service
                        ? service.healthStatus() == HealthStatus.UNKNOWN
                                ? new ProjectedValue.Unknown(ProjectedValue.Unknown.STATE_UNKNOWN)
                                : new ProjectedValue.Text(service.healthStatus().name())
                        : mismatch();
            case LAG ->
                result instanceof QueueInspectResultV1 queue
                        ? group(queue, consumerGroup)
                                .map(group -> number(group.lag()))
                                .orElseGet(RecoveryProjection::groupMissing)
                        : mismatch();
            case PENDING_COUNT ->
                result instanceof QueueInspectResultV1 queue
                        ? group(queue, consumerGroup)
                                .<ProjectedValue>map(group -> new ProjectedValue.Number(group.pendingCount()))
                                .orElseGet(RecoveryProjection::groupMissing)
                        : mismatch();
            case LATEST -> result instanceof MetricsQueryResultV1 metrics ? number(metrics.latest()) : mismatch();
        };
    }

    /** 精确匹配组名；不回退到数组第一项。 */
    private static Optional<QueueInspectResultV1.ConsumerGroup> group(
            QueueInspectResultV1 queue, String consumerGroup) {
        if (consumerGroup == null) {
            return Optional.empty();
        }
        return queue.consumerGroups().stream()
                .filter(group -> group.group().equals(consumerGroup))
                .findFirst();
    }

    private static ProjectedValue number(java.lang.Number value) {
        if (value == null) {
            return new ProjectedValue.Unknown(ProjectedValue.Unknown.FIELD_NULL);
        }
        double number = value.doubleValue();
        return Double.isFinite(number)
                ? new ProjectedValue.Number(number)
                : new ProjectedValue.Unknown(ProjectedValue.Unknown.NOT_A_NUMBER);
    }

    private static ProjectedValue groupMissing() {
        return new ProjectedValue.Unknown(ProjectedValue.Unknown.GROUP_MISSING);
    }

    private static ProjectedValue mismatch() {
        return new ProjectedValue.Unknown(ProjectedValue.Unknown.RESULT_MISMATCH);
    }
}
