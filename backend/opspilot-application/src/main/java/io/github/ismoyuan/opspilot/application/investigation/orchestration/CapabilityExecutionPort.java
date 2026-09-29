package io.github.ismoyuan.opspilot.application.investigation.orchestration;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RequestCapability;

/**
 * 调查 OBSERVE 能力的执行入口（07 §41～§42、§56）：在短事务内按 Incident → Investigation 锁序完成准入 Gate，提交后才调用 Provider。
 * 编排器在 Step 结果提交之后调用，不在任何事务内。实现为 {@link InvestigationCapabilityExecutor}（08 TASK-058）。
 */
public interface CapabilityExecutionPort {

    CapabilityRequestResult execute(long incidentId, int runNo, long stepId, RequestCapability request);

    /**
     * 补完已退出 Worker 留下的 RUNNING 调查调用；只能由持有该 Incident Worker 拥有权的调用方在准入新 Step 之前调用，失败时抛出。
     *
     * @return 补完的条数
     */
    int closeOrphanedCalls(long incidentId);
}
