package io.github.ismoyuan.opspilot.application.investigation.orchestration;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RequestCapability;

/**
 * 调查 OBSERVE 能力的执行入口（07 §41～§42、§56）：在短事务内按 Incident → Investigation 锁序完成准入 Gate，提交后才调用 Provider。
 * 编排器在 Step 结果提交之后调用，不在任何事务内。当前实现只有准入 Gate（Fake，08 TASK-040）；真实准入登记 Invocation 与扣预算属
 * TASK-048，真实 Provider 与 Observation 属 TASK-058。
 */
public interface CapabilityExecutionPort {

    CapabilityRequestResult execute(long incidentId, int runNo, long stepId, RequestCapability request);
}
