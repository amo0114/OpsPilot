package io.github.ismoyuan.opspilot.application.approval;

import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRepository.ExecutionRef;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;

/**
 * 决定后的 Approval 与 Incident（05 §39、§41～§43）。
 *
 * @param execution 批准创建的 Execution；拒绝与撤回为空
 * @param replayed 同一操作者重复提交相同决定与说明，返回的是原决定（05 §43），本次没有任何写入
 */
public record ApprovalDecisionResult(
        long approvalId,
        ApprovalStatus approvalStatus,
        long approvalVersion,
        IncidentKey incidentKey,
        IncidentStatus incidentStatus,
        long incidentVersion,
        ExecutionRef execution,
        boolean replayed) {}
