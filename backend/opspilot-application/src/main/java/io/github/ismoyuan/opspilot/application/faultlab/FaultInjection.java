package io.github.ismoyuan.opspilot.application.faultlab;

import java.time.Instant;
import java.util.Objects;

/**
 * 注入器施加故障后的事实（只供 Fault Lab 写入实验与 Ground Truth，不进入 Incident 或任何产品响应）。
 *
 * @param startedAt 故障真正开始生效的时间（Incident.started_at，09 §19）
 * @param stoppedContainerId 停止消费者类场景被停止的容器 id，写入 Ground Truth（09 §63）；其他场景为空
 */
public record FaultInjection(Instant startedAt, String stoppedContainerId) {

    public FaultInjection {
        Objects.requireNonNull(startedAt, "startedAt");
    }

    public static FaultInjection startedAt(Instant startedAt) {
        return new FaultInjection(startedAt, null);
    }
}
