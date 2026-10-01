package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.domain.capability.RiskLevel;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.remediation.RemediationPlanStatus;
import java.time.Instant;

/**
 * “处理建议”（00 §26、05 §32、§37、§45）：该 Incident 最新的 Plan 及其唯一 Action、Approval 与 Execution。方案状态如实给出
 * （ACTIVE、SUPERSEDED、CANCELLED、EXECUTED）；EXECUTED 与 Execution SUCCEEDED 都只表示操作已执行，不表示故障已恢复。
 *
 * @param approval 尚未创建审批时为空
 * @param execution 尚未执行时为空
 */
public record RemediationView(Plan plan, Action action, Approval approval, Execution execution) {

    public record Plan(long planId, String title, String summary, RemediationPlanStatus status, Instant createdAt) {}

    /** 风险与是否审批由 Java Registry 决定并持久化（05 §30）。 */
    public record Action(
            long actionId,
            String capabilityKey,
            String targetResourceKey,
            String targetResourceName,
            String summary,
            RiskLevel riskLevel,
            String expectedImpactSummary,
            boolean requiresApproval) {}

    /** @param decidedAt PENDING 为空 */
    public record Approval(
            long approvalId, ApprovalStatus status, long version, Instant requestedAt, Instant decidedAt) {}

    /**
     * @param startedAt PENDING 为空
     * @param finishedAt 未结束为空
     * @param errorCode 失败时的错误码（如 EXECUTION_RESULT_UNCERTAIN），其余为空
     */
    public record Execution(
            long executionId, ActionExecutionStatus status, Instant startedAt, Instant finishedAt, String errorCode) {}
}
