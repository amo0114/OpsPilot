package io.github.ismoyuan.opspilot.application.execution;

import io.github.ismoyuan.opspilot.application.capability.ProviderBinding;
import io.github.ismoyuan.opspilot.domain.system.binding.DockerResourceBindingV1;
import java.util.Objects;

/**
 * service.restart.execution-context / 1（04 §45）：受信解析的执行目标。PENDING 创建时记录目标资源与唯一 Provider 的资源绑定、
 * 数据源连接和配置的容器名；真实容器 id 在 RUNNING 准入前解析（TASK-071），此前为空。不含密码、端点或任意命令，也不来自 AI。
 *
 * @param containerId RUNNING 准入前解析出的容器身份；PENDING 时为空
 */
public record ServiceRestartExecutionContextV1(
        long targetResourceId,
        long resourceBindingId,
        long dataSourceConnectionId,
        String containerName,
        String containerId) {

    public static final String SCHEMA_NAME = "service.restart.execution-context";
    public static final int SCHEMA_VERSION = 1;

    public ServiceRestartExecutionContextV1 {
        if (targetResourceId < 1 || resourceBindingId < 1 || dataSourceConnectionId < 1) {
            throw new IllegalArgumentException("execution context ids must be positive");
        }
        Objects.requireNonNull(containerName, "containerName");
        if (containerName.isBlank()) {
            throw new IllegalArgumentException("containerName must not be blank");
        }
        if (containerId != null && containerId.isBlank()) {
            throw new IllegalArgumentException("containerId must not be blank");
        }
    }

    /** 批准事务中由 service.restart 的唯一 Provider Binding 得出（06 §106）。 */
    public static ServiceRestartExecutionContextV1 pending(long targetResourceId, ProviderBinding provider) {
        return new ServiceRestartExecutionContextV1(
                targetResourceId,
                provider.binding().id(),
                provider.connection().id(),
                ((DockerResourceBindingV1) provider.selector()).containerName(),
                null);
    }
}
