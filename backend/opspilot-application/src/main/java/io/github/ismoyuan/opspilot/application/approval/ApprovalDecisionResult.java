package io.github.ismoyuan.opspilot.application.approval;

import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;

/** 拒绝 / 撤回后的 Approval 与 Incident。 */
public record ApprovalDecisionResult(
        long approvalId,
        ApprovalStatus approvalStatus,
        long approvalVersion,
        IncidentKey incidentKey,
        IncidentStatus incidentStatus,
        long incidentVersion) {}
