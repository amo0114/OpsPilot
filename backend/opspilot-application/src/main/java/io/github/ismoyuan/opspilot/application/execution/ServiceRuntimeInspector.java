package io.github.ismoyuan.opspilot.application.execution;

import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import java.time.Instant;
import java.util.Objects;

/**
 * 执行结果核对使用的只读运行时检查（04 §82、07 §66～§67）：按执行准入时解析的容器 id 读取当前身份、运行状态与启动时间。只读、不重试，
 * 在数据库事务之外调用；不创建 CapabilityInvocation 或 Observation，也不经过调查的能力执行服务。核对方只依赖本端口，不持有写操作端口，
 * 因此核对分支在结构上无法重发 CHANGE。
 */
public interface ServiceRuntimeInspector {

    /**
     * @param connection 执行上下文记录的 Docker 数据源连接
     * @param containerId 执行准入时解析并保存的完整容器 id
     */
    RuntimeInspection inspect(DataSourceConnection connection, String containerId, Instant deadline);

    sealed interface RuntimeInspection {}

    /**
     * @param containerId Docker 返回的容器 id
     * @param startedAt 未知或从未启动时为空
     */
    record Inspected(String containerId, RuntimeState runtimeState, Instant startedAt) implements RuntimeInspection {

        public Inspected {
            Objects.requireNonNull(containerId, "containerId");
            Objects.requireNonNull(runtimeState, "runtimeState");
        }
    }

    /** 没有得到可用数据（容器不存在、连接失败、超时、响应非法）；message 为固定文案。 */
    record NotInspected(ErrorCode code, String message) implements RuntimeInspection {}
}
