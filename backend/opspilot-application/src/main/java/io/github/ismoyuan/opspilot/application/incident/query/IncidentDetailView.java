package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.application.investigation.query.InvestigationOverviewView.RunBudgetView;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryStatusView;
import io.github.ismoyuan.opspilot.domain.incident.IncidentAction;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.time.Instant;
import java.util.List;

/**
 * 故障详情默认页面数据（05 §23、00 §26、08 TASK-085～086）：当前影响、当前状态、当前判断及依据、处理建议、恢复情况，以及 Java
 * 计算的 availableActions。全部字段来自同一一致性读取（05 §65、§67）：lastTimelineEventId 是该 Snapshot 对应的 Timeline 游标，
 * SSE 从它之后追赶。
 *
 * @param description 可为空
 * @param resolvedAt 可为空
 * @param investigation 尚未开始调查时为空
 * @param currentAssessment 尚无 Diagnosis 时为空
 * @param remediation 尚无处理方案时为空
 * @param recovery 尚无恢复验证时为空
 */
public record IncidentDetailView(
        String incidentKey,
        String title,
        String description,
        String systemKey,
        String systemName,
        IncidentStatus status,
        long version,
        long lastTimelineEventId,
        String impactSummary,
        Instant startedAt,
        Instant detectedAt,
        Instant resolvedAt,
        List<AffectedResourceView> affectedResources,
        Investigation investigation,
        CurrentAssessmentView currentAssessment,
        RemediationView remediation,
        RecoveryStatusView recovery,
        List<IncidentAction> availableActions) {

    public IncidentDetailView {
        affectedResources = List.copyOf(affectedResources);
        availableActions = List.copyOf(availableActions);
    }

    /**
     * 调查进度（05 §50 的子集）：budget 是当前 run 的额度，totalCapabilityCalls 是历史累计，两者分开；预算不是进度。
     *
     * @param startedAt 首次调查开始
     * @param currentRunStartedAt 本轮开始（本轮 deadline 的起点）
     */
    public record Investigation(
            int runNo,
            boolean stopRequested,
            Instant startedAt,
            Instant currentRunStartedAt,
            RunBudgetView budget,
            long totalCapabilityCalls) {}
}
