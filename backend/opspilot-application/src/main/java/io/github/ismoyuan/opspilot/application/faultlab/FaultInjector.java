package io.github.ismoyuan.opspilot.application.faultlab;

import java.time.Instant;

/**
 * 一个场景的真实注入器（09 §13～§19）。真实实现属 TASK-093（S3）、TASK-094（S1）、TASK-095（S2）：只作用于独立的 Demo 环境，控制日志与
 * 容器信息不进入被调查资源的数据源（09 §22）。三个方法都在数据库事务之外调用，失败抛出 {@link FaultInjectionException}。
 */
public interface FaultInjector {

    /** 所负责的场景键（{@link FaultScenarioCatalog}）。 */
    String scenarioKey();

    /**
     * 是否控制该系统的该目标资源。真实注入器只绑定配置的 Demo 靶场；不控制的系统在创建实验或 Reset 之前即被拒绝（B34-R1 P1），
     * 不能因为资源同名就操作另一套环境。
     */
    boolean controls(String systemKey, String targetResourceKey);

    /** 施加故障（真实注入器先完成 Preflight 与 Baseline，09 §13～§16）。@return 故障生效时间及写入 Ground Truth 的事实 */
    FaultInjection inject(FaultTarget target);

    /**
     * 确认故障真的产生了预期症状（09 §18）；未达到时抛出异常，实验记为 FAILED 且不创建 Incident。
     *
     * @return 首次确认预期异常的时间（Incident.detected_at）
     */
    Instant verifyInjected(FaultTarget target);

    /** 只恢复本场景注入的实验环境（05 §72），不改变任何 Incident。 */
    void reset(FaultTarget target);
}
