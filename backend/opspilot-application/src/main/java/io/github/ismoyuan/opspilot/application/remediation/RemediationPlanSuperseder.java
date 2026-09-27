package io.github.ismoyuan.opspilot.application.remediation;

import java.time.Instant;

/**
 * 新 Diagnosis 创建时，同一事务内使该 Incident 所有尚未执行的 RemediationPlan 失效（SUPERSEDED）的端口（08 TASK-026）：
 * 旧方案基于旧诊断，不得再被批准执行。调用方已持有 Incident 行锁。Plan 表建立前为占位实现，
 * TASK-062/067 回填真实行为与集成断言。
 */
public interface RemediationPlanSuperseder {

    void supersedeUnexecutedPlans(long incidentId, long newDiagnosisId, Instant at);
}
