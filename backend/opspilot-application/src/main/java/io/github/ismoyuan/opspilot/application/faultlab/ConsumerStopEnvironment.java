package io.github.ismoyuan.opspilot.application.faultlab;

import java.time.Duration;
import java.time.Instant;

/**
 * S3 注入器操作的 Demo 靶场（09 §60～§65）：消费者容器、统计 Stream 与跳转接口。只属于 Fault Lab 的控制面，配置独立于被调查资源的数据源；
 * 不经过 Capability、不产生 Observation。失败抛出 {@link FaultInjectionException}（固定、脱敏的说明，不含端点与凭据）。每个方法都接受调用
 * 必须结束的期限 {@code deadline}：实现把等待裁剪到期限（另有自身的单次调用上限），期限已到时立即失败。
 */
public interface ConsumerStopEnvironment {

    /** 消费者容器当前状态（按配置的容器名）。 */
    ConsumerContainer inspectConsumer(Instant deadline);

    /** 经 Docker Engine API 停止该容器（不是 pause，09 §64）；Docker 先发 SIGTERM，宽限期后强制结束。 */
    void stopConsumer(String containerId, Duration grace, Instant deadline);

    /** 启动已停止的该容器（Reset）。 */
    void startConsumer(String containerId, Instant deadline);

    /** 统计 Stream 与消费组的统计量（不读取消息正文）。 */
    StreamSnapshot readStream(Instant deadline);

    /** 访问一次演示短链：成功指返回跳转且目标不是 notfound 页面。这是真实访问，ShortLink 会为它写入一条统计消息。 */
    RedirectProbe probeRedirect(Instant deadline);

    /** 容器运行状态。 */
    enum RuntimeState {
        RUNNING,
        STOPPED,
        RESTARTING,
        PAUSED,
        UNKNOWN
    }

    /** 容器健康检查状态；未配置健康检查为 NOT_CONFIGURED。 */
    enum HealthState {
        HEALTHY,
        UNHEALTHY,
        STARTING,
        NOT_CONFIGURED
    }

    /**
     * @param finishedAt 最近一次退出时间，从未退出为空
     */
    record ConsumerContainer(
            String containerId, RuntimeState runtimeState, HealthState health, Instant startedAt, Instant finishedAt) {

        boolean healthyRunning() {
            return runtimeState == RuntimeState.RUNNING
                    && (health == HealthState.HEALTHY || health == HealthState.NOT_CONFIGURED);
        }
    }

    /**
     * @param lastGeneratedMillis 最近生成条目 ID 的毫秒部分，从未生成为空
     * @param lastGeneratedSequence 最近生成条目 ID 的序号部分
     * @param entriesAdded Stream 累计写入的条目数；Redis 不提供时为空
     * @param lag 消费组未投递条目数；Redis 不能给出时为空（不当 0，09 §10）
     * @param pending 消费组已投递未确认条目数
     */
    record StreamSnapshot(
            Instant observedAt,
            Long lastGeneratedMillis,
            long lastGeneratedSequence,
            Long entriesAdded,
            Long lag,
            long pending) {

        /** 最近生成 ID 是否严格大于另一次采样（生产者仍在写入）。 */
        boolean generatedAfter(StreamSnapshot earlier) {
            if (lastGeneratedMillis == null) {
                return false;
            }
            if (earlier.lastGeneratedMillis == null) {
                return true;
            }
            int millis = Long.compare(lastGeneratedMillis, earlier.lastGeneratedMillis);
            return millis > 0 || (millis == 0 && lastGeneratedSequence > earlier.lastGeneratedSequence);
        }
    }

    record RedirectProbe(boolean succeeded, Duration latency) {}
}
