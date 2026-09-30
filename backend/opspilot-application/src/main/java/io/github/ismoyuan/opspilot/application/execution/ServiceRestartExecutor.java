package io.github.ismoyuan.opspilot.application.execution;

import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import java.time.Instant;
import java.util.Objects;

/**
 * service.restart 的执行端口（08 TASK-070、06 §101～§109）。两次调用都在数据库事务之外进行，由 Worker 控制准入（TASK-071）：
 * {@link #resolveTarget} 只读地把配置的容器名解析为真实容器 id（RUNNING 准入前）；{@link #restart} 以该 id 发出一次写请求，
 * 不重试。结果严格区分三类：成功；确定失败（请求没有发出，或 Docker 明确拒绝）；结果未知（请求开始写出之后超时、断连或响应不可读）——
 * 未知不能当作失败或“未发送”，由有界只读核对处理（04 §82、TASK-072）。
 */
public interface ServiceRestartExecutor {

    /**
     * @param connection 执行上下文记录的 Docker 数据源连接
     */
    TargetResolution resolveTarget(DataSourceConnection connection, String containerName, Instant deadline);

    RestartOutcome restart(DataSourceConnection connection, String containerId, Instant deadline);

    sealed interface TargetResolution {}

    record Resolved(String containerId) implements TargetResolution {

        public Resolved {
            Objects.requireNonNull(containerId, "containerId");
        }
    }

    /** 目标无法解析；只读，没有发出任何写请求。 */
    record Unresolved(ErrorCode code, String message) implements TargetResolution {}

    sealed interface RestartOutcome {}

    record Succeeded(ServiceRestartResultV1 result) implements RestartOutcome {}

    /** 确定没有生效或 Docker 明确拒绝；message 为固定文案，不含端点或响应内容。 */
    record Failed(ErrorCode code, String message) implements RestartOutcome {}

    /** 请求可能已被执行；code 只说明观察到的现象（TIMEOUT、CONNECTION_FAILED 等）。 */
    record Uncertain(ErrorCode code, String message) implements RestartOutcome {}
}
