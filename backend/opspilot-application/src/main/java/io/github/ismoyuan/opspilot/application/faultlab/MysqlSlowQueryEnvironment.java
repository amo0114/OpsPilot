package io.github.ismoyuan.opspilot.application.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RedirectProbe;
import java.time.Instant;

/**
 * S2 注入器操作的 Demo 靶场（09 §42～§52、§59）：project-api 的 Demo-only 统计快照刷新负载（经应用自身 Hikari Pool 执行慢存储过程）、
 * 该连接池的实际指标、MySQL Performance Schema 的语句摘要（Demo 控制凭证）与创建接口。只属于 Fault Lab 控制面；不经过 Capability、不产生
 * Observation。失败抛出 {@link FaultInjectionException}（固定、脱敏的说明）。每个方法都接受调用必须结束的期限。
 */
public interface MysqlSlowQueryEnvironment {

    WorkloadState workload(Instant deadline);

    /** 启动 {@code workers} 个刷新任务，每次在数据库侧持续约 {@code statementMillis} 毫秒。 */
    void startWorkload(int workers, int statementMillis, Instant deadline);

    /** 停止刷新负载并等待进行中的刷新结束（09 §59）。 */
    WorkloadState stopWorkload(Instant deadline);

    /** project-api 自身连接池的实际指标。 */
    PoolState pool(Instant deadline);

    /** 慢存储过程语句摘要（未执行过时 executions 为 0）。 */
    SlowStatement slowStatement(Instant deadline);

    /** 以 Demo 控制凭证清理语句摘要汇总（09 §52）。 */
    void clearStatementSummary(Instant deadline);

    /** 创建接口累计请求数（含失败）。 */
    long createRequests(Instant deadline);

    /** 创建一个唯一演示短链（真实请求）：成功指 HTTP 200 且业务码成功。 */
    RedirectProbe probeCreate(Instant deadline);

    record WorkloadState(boolean running, int inFlight) {}

    record PoolState(int active, int pending, int max) {}

    record SlowStatement(long executions, long avgMillis, long maxMillis) {}
}
