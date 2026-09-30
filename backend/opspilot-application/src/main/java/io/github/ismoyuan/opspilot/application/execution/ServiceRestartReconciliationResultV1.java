package io.github.ismoyuan.opspilot.application.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * service.restart.reconciliation / 1（04 §45、§82）：restart 结果未知后，只读核对观察到同一容器在本次执行开始之后启动。与
 * {@link ServiceRestartResultV1} 一样只表示“重启操作已生效”，不表示业务已恢复；它不声称知道 Docker 何时完成请求。
 *
 * @param containerId 执行准入时解析、核对时再次确认的容器
 * @param executionStartedAt Execution 进入 RUNNING 的时间（发出 restart 之前）
 * @param serviceStartedAt 核对读到的容器启动时间，晚于 executionStartedAt
 * @param attemptNo 确认启动效果的核对序号
 */
public record ServiceRestartReconciliationResultV1(
        String provider,
        String containerId,
        Instant executionStartedAt,
        Instant serviceStartedAt,
        Instant confirmedAt,
        int attemptNo) {

    public static final String SCHEMA_NAME = "service.restart.reconciliation";
    public static final int SCHEMA_VERSION = 1;

    public ServiceRestartReconciliationResultV1 {
        if (!ServiceRestartResultV1.DOCKER.equals(provider)) {
            throw new IllegalArgumentException("provider must be DOCKER");
        }
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(executionStartedAt, "executionStartedAt");
        Objects.requireNonNull(serviceStartedAt, "serviceStartedAt");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (!serviceStartedAt.isAfter(executionStartedAt)) {
            throw new IllegalArgumentException("serviceStartedAt must be after executionStartedAt");
        }
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
    }
}
