package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.application.approval.PendingApprovalQuery;
import io.github.ismoyuan.opspilot.application.remediation.RemediationActions;
import io.github.ismoyuan.opspilot.application.remediation.RemediationContextQuery;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.incident.IncidentAction;
import io.github.ismoyuan.opspilot.domain.incident.IncidentActionPolicy;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 读取 {@link IncidentActionPolicy} 需要的事实（08 TASK-086）：须在调用方的只读事务中执行，与 Snapshot 同一一致性读取。只在 DIAGNOSED
 * 时才需要的事实（待审批、处理建议是否可用）只在该状态读取。“处理建议可用”与请求处理建议的前置拒绝一致（RemediationDraftContextBuilder：
 * 最新 Diagnosis 不是 UNDETERMINED，且 {@link RemediationActions#allowedActionsForDiagnosis} 非空），S1/S2 这类没有可用写动作的诊断
 * 不提供该动作（05 §30）。
 */
@Service
public class AvailableActionsResolver {

    private final PendingApprovalQuery pendingApprovals;
    private final RemediationContextQuery remediationContext;
    private final RemediationActions remediationActions;

    public AvailableActionsResolver(
            PendingApprovalQuery pendingApprovals,
            RemediationContextQuery remediationContext,
            RemediationActions remediationActions) {
        this.pendingApprovals = pendingApprovals;
        this.remediationContext = remediationContext;
        this.remediationActions = remediationActions;
    }

    public List<IncidentAction> resolve(IncidentSnapshot incident) {
        boolean diagnosed = incident.status() == IncidentStatus.DIAGNOSED;
        return IncidentActionPolicy.available(
                incident.status(),
                incident.stopRequested(),
                diagnosed && pendingApprovals.existsPending(incident.incidentId()),
                diagnosed && remediationAvailable(incident));
    }

    private boolean remediationAvailable(IncidentSnapshot incident) {
        return remediationContext
                .findLatestDiagnosis(incident.incidentId())
                .filter(diagnosis -> diagnosis.conclusionType() != DiagnosisConclusionType.UNDETERMINED)
                .map(diagnosis -> !remediationActions
                        .allowedActionsForDiagnosis(
                                incident.managedSystemId(),
                                incident.incidentId(),
                                remediationContext.findFrozenEvidence(diagnosis.id()))
                        .isEmpty())
                .orElse(false);
    }
}
