package io.github.ismoyuan.opspilot.application.remediation;

import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;

/** 05 §32 成功结果：迁移后的 Incident、新 Plan / Action 与 PENDING Approval。 */
public record RequestRemediationResult(
        IncidentKey incidentKey,
        IncidentStatus status,
        long version,
        long planId,
        long actionId,
        long approvalId,
        ValidatedRemediationProposal proposal) {}
