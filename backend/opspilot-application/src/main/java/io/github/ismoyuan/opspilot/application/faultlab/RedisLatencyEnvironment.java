package io.github.ismoyuan.opspilot.application.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RedirectProbe;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.StreamSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * S1 注入器操作的 Demo 靶场（09 §28～§33）：ShortLink 与 OpsPilot 共用的 Redis Toxiproxy 代理、经该代理的 PING、统计 Stream 与跳转接口。
 * 只属于 Fault Lab 控制面（Toxiproxy 控制 API 不进入被调查资源的数据源）；不经过 Capability、不产生 Observation。失败抛出
 * {@link FaultInjectionException}（固定、脱敏的说明）。每个方法都接受调用必须结束的期限，实现把等待裁剪到期限。
 */
public interface RedisLatencyEnvironment {

    /** 代理当前状态。 */
    ProxyState inspectProxy(Instant deadline);

    /** 在代理上增加 downstream latency toxic（明确方向，不依赖“自动双向”，09 §30）。 */
    void addLatency(LatencyToxic toxic, Instant deadline);

    /** 删除指定 toxic。@return 删除前是否存在 */
    boolean removeLatency(String toxicName, Instant deadline);

    /** 经业务同一代理连续发出 {@code count} 次 PING，返回每次往返耗时（09 §33）。 */
    List<Duration> pingThroughProxy(int count, Instant deadline);

    /** 统计 Stream 与消费组的统计量（直连 Redis，不经代理）。 */
    StreamSnapshot readStream(Instant deadline);

    /** 访问一次演示短链（真实访问，ShortLink 会为它写入一条统计消息）。 */
    RedirectProbe probeRedirect(Instant deadline);

    /** @param toxicNames 代理上现有 toxic 的名称 */
    record ProxyState(boolean enabled, List<String> toxicNames) {

        public ProxyState {
            toxicNames = List.copyOf(toxicNames);
        }
    }

    /** downstream latency toxic（toxicity 0～1）。 */
    record LatencyToxic(String name, int latencyMs, int jitterMs, double toxicity) {}
}
