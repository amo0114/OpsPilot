package io.github.ismoyuan.opspilot.web.incident;

import io.github.ismoyuan.opspilot.application.incident.query.AffectedResourceView;
import io.github.ismoyuan.opspilot.application.incident.query.CurrentAssessmentView;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentDetailView;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentSummaryView;
import io.github.ismoyuan.opspilot.application.incident.query.RemediationView;
import io.github.ismoyuan.opspilot.application.recovery.CriterionReason;
import io.github.ismoyuan.opspilot.application.recovery.CriterionResult;
import io.github.ismoyuan.opspilot.application.recovery.ProjectedValue;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySample;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryStatusView;
import io.github.ismoyuan.opspilot.domain.capability.RiskLevel;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentAction;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.remediation.RemediationPlanStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import io.github.ismoyuan.opspilot.web.response.ApiTimes;
import java.util.List;

/** Incident API 响应 DTO（05 §20～§28、§33）；与领域对象、数据库行分开（07 §28）。不暴露内部 Incident id。 */
final class IncidentResponses {

    private IncidentResponses() {}

    /** 创建结果（05 §20），availableActions 由 Java 计算（08 TASK-086）。 */
    record CreateIncidentResponse(
            String incidentKey, IncidentStatus status, long version, List<IncidentAction> availableActions) {}

    /** 取消的结果。 */
    record IncidentStateResponse(String incidentKey, IncidentStatus status, long version) {}

    record StartInvestigationResponse(
            String incidentKey, IncidentStatus status, long version, boolean investigationStarted) {}

    /** continue/stop 的结果（05 §27～§28）。 */
    record InvestigationRunResponse(
            String incidentKey, IncidentStatus status, long version, int runNo, boolean stopRequested) {}

    record IncidentSummaryResponse(
            String incidentKey,
            String title,
            String systemName,
            IncidentStatus status,
            String impactSummary,
            String detectedAt,
            String updatedAt) {

        static IncidentSummaryResponse of(IncidentSummaryView view) {
            return new IncidentSummaryResponse(
                    view.incidentKey(),
                    view.title(),
                    view.systemName(),
                    view.status(),
                    view.impactSummary(),
                    ApiTimes.format(view.detectedAt()),
                    ApiTimes.format(view.updatedAt()));
        }
    }

    /**
     * 故障详情（05 §23、00 §26、08 TASK-085～086）：当前影响 → 当前状态 → 当前判断及依据 → 处理建议 → 恢复情况，以及 availableActions。
     * 同一一致性读取；lastTimelineEventId 是 SSE 追赶的起点（05 §65）。没有对应事实的节为 null，不返回占位值。
     */
    record IncidentDetailResponse(
            String incidentKey,
            String title,
            String description,
            SystemRef system,
            IncidentStatus status,
            String statusLabel,
            long version,
            long lastTimelineEventId,
            Impact impact,
            String resolvedAt,
            List<AffectedResource> affectedResources,
            Investigation investigation,
            Assessment currentAssessment,
            Remediation remediation,
            Recovery recovery,
            List<IncidentAction> availableActions) {

        record SystemRef(String systemKey, String name) {}

        record Impact(String summary, String startedAt, String detectedAt) {}

        record AffectedResource(String resourceKey, String name, ResourceType resourceType) {}

        record ResourceRef(String resourceKey, String name) {}

        /** 当前 run 的额度与历史累计分开（05 §50）；预算不是进度。 */
        record Investigation(
                int runNo,
                boolean stopRequested,
                String startedAt,
                String currentRunStartedAt,
                Budget budget,
                long totalCapabilityCalls) {}

        record Budget(
                String scope,
                int capabilityCallsUsed,
                int capabilityCallsLimit,
                int remainingCapabilityCalls,
                long durationSeconds,
                int durationLimitSeconds) {}

        record Assessment(
                int diagnosisVersion,
                int runNo,
                DiagnosisConclusionType conclusionType,
                String summary,
                List<String> why,
                TerminationReason terminationReason,
                String createdAt) {}

        record Remediation(Plan plan, Action action, Approval approval, Execution execution) {}

        record Plan(long planId, String title, String summary, RemediationPlanStatus status, String createdAt) {}

        record Action(
                long actionId,
                String capabilityKey,
                ResourceRef targetResource,
                String summary,
                RiskLevel riskLevel,
                String expectedImpactSummary,
                boolean requiresApproval) {}

        record Approval(long approvalId, ApprovalStatus status, long version, String requestedAt, String decidedAt) {}

        /** “操作执行成功”不是“故障已恢复”（05 §45）。 */
        record Execution(
                long executionId,
                ActionExecutionStatus status,
                String startedAt,
                String finishedAt,
                String errorCode) {}

        /** Verification 的整体业务状态与三值检查分开（05 §48）。 */
        record Recovery(
                int verificationNo,
                RecoveryVerificationStatus status,
                String resultSummary,
                ResourceRef resource,
                Policy policy,
                boolean afterExecution,
                String deadlineAt,
                String startedAt,
                String finishedAt,
                List<Check> checks) {}

        record Policy(String policyKey, String name, int version) {}

        /** result、reason 在 Verification 进行中为 null。 */
        record Check(
                String criterionKey,
                String name,
                boolean required,
                CriterionResult result,
                CriterionReason reason,
                List<Sample> samples) {}

        /**
         * value 是成功样本按注册投影取得的数值或枚举名；取不到可靠值时为 null 且 valueUnknownCause 说明原因，不把“没有数据”显示成 0。
         */
        record Sample(
                int sampleIndex,
                RecoverySample.Status status,
                String sampledAt,
                Object value,
                String valueUnknownCause,
                String errorCode) {}

        static IncidentDetailResponse of(IncidentDetailView view) {
            return new IncidentDetailResponse(
                    view.incidentKey(),
                    view.title(),
                    view.description(),
                    new SystemRef(view.systemKey(), view.systemName()),
                    view.status(),
                    statusLabel(view.status()),
                    view.version(),
                    view.lastTimelineEventId(),
                    new Impact(
                            view.impactSummary(),
                            ApiTimes.format(view.startedAt()),
                            ApiTimes.format(view.detectedAt())),
                    ApiTimes.format(view.resolvedAt()),
                    view.affectedResources().stream()
                            .map(IncidentDetailResponse::affected)
                            .toList(),
                    view.investigation() == null ? null : investigation(view.investigation()),
                    view.currentAssessment() == null ? null : assessment(view.currentAssessment()),
                    view.remediation() == null ? null : remediation(view.remediation()),
                    view.recovery() == null ? null : recovery(view.recovery()),
                    view.availableActions());
        }

        private static AffectedResource affected(AffectedResourceView view) {
            return new AffectedResource(view.resourceKey(), view.name(), view.resourceType());
        }

        private static Investigation investigation(IncidentDetailView.Investigation view) {
            var budget = view.budget();
            return new Investigation(
                    view.runNo(),
                    view.stopRequested(),
                    ApiTimes.format(view.startedAt()),
                    ApiTimes.format(view.currentRunStartedAt()),
                    new Budget(
                            "ACTIVE_RUN",
                            budget.capabilityCallsUsed(),
                            budget.capabilityCallsLimit(),
                            budget.remainingCapabilityCalls(),
                            budget.durationSeconds(),
                            budget.durationLimitSeconds()),
                    view.totalCapabilityCalls());
        }

        private static Assessment assessment(CurrentAssessmentView view) {
            return new Assessment(
                    view.diagnosisVersion(),
                    view.runNo(),
                    view.conclusionType(),
                    view.summary(),
                    view.why(),
                    view.terminationReason(),
                    ApiTimes.format(view.createdAt()));
        }

        private static Remediation remediation(RemediationView view) {
            var plan = view.plan();
            var action = view.action();
            var approval = view.approval();
            var execution = view.execution();
            return new Remediation(
                    new Plan(
                            plan.planId(),
                            plan.title(),
                            plan.summary(),
                            plan.status(),
                            ApiTimes.format(plan.createdAt())),
                    new Action(
                            action.actionId(),
                            action.capabilityKey(),
                            new ResourceRef(action.targetResourceKey(), action.targetResourceName()),
                            action.summary(),
                            action.riskLevel(),
                            action.expectedImpactSummary(),
                            action.requiresApproval()),
                    approval == null
                            ? null
                            : new Approval(
                                    approval.approvalId(),
                                    approval.status(),
                                    approval.version(),
                                    ApiTimes.format(approval.requestedAt()),
                                    ApiTimes.format(approval.decidedAt())),
                    execution == null
                            ? null
                            : new Execution(
                                    execution.executionId(),
                                    execution.status(),
                                    ApiTimes.format(execution.startedAt()),
                                    ApiTimes.format(execution.finishedAt()),
                                    execution.errorCode()));
        }

        private static Recovery recovery(RecoveryStatusView view) {
            return new Recovery(
                    view.verificationNo(),
                    view.status(),
                    view.resultSummary(),
                    new ResourceRef(view.resourceKey(), view.resourceName()),
                    new Policy(view.policyKey(), view.policyName(), view.policyVersion()),
                    view.afterExecution(),
                    ApiTimes.format(view.deadlineAt()),
                    ApiTimes.format(view.startedAt()),
                    ApiTimes.format(view.finishedAt()),
                    view.checks().stream()
                            .map(check -> new Check(
                                    check.criterionKey(),
                                    check.name(),
                                    check.required(),
                                    check.result(),
                                    check.reason(),
                                    check.samples().stream()
                                            .map(IncidentDetailResponse::sample)
                                            .toList()))
                            .toList());
        }

        private static Sample sample(RecoveryStatusView.Sample sample) {
            Object value = null;
            String unknown = null;
            switch (sample.value()) {
                case null -> {}
                case ProjectedValue.Number number -> value = number(number.value());
                case ProjectedValue.Text text -> value = text.value();
                case ProjectedValue.Unknown cause -> unknown = cause.cause();
            }
            return new Sample(
                    sample.sampleIndex(),
                    sample.status(),
                    ApiTimes.format(sample.sampledAt()),
                    value,
                    unknown,
                    sample.errorCode());
        }

        /**
         * 整数值（如积压条数）按整数输出，不写成 2180.0；含小数或不在 long 范围 [-2^63, 2^63) 内的值按小数输出。用 if/else 而不是条件表达式：
         * {@code cond ? Long : Double} 会把两侧拆箱并提升为 double，整数也会变成 2180.0（B31-R1 P3）。
         */
        private static Number number(double value) {
            // 先限定在 long 范围 [-2^63, 2^63)：(long) 2^63 会饱和为 Long.MAX_VALUE，比较时又提升回 2^63，误判为整数
            if (value >= -0x1.0p63 && value < 0x1.0p63) {
                long whole = (long) value;
                if (whole == value) {
                    return whole;
                }
            }
            return value;
        }

        /** 01 §3 的中文状态名。 */
        static String statusLabel(IncidentStatus status) {
            return switch (status) {
                case CREATED -> "待调查";
                case INVESTIGATING -> "调查中";
                case DIAGNOSED -> "已有诊断";
                case AWAITING_APPROVAL -> "等待批准";
                case EXECUTING -> "正在处理";
                case VERIFYING -> "正在验证恢复";
                case RESOLVED -> "已恢复";
                case CANCELLED -> "已取消";
            };
        }
    }
}
