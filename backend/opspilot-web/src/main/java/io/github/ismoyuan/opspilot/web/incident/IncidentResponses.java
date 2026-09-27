package io.github.ismoyuan.opspilot.web.incident;

import io.github.ismoyuan.opspilot.application.incident.query.AffectedResourceView;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentDetailView;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentSummaryView;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import io.github.ismoyuan.opspilot.web.response.ApiTimes;
import java.util.List;

/** Incident API 响应 DTO（05 §20～§28、§33）；与领域对象、数据库行分开（07 §28）。 */
final class IncidentResponses {

    private IncidentResponses() {}

    /** 创建与取消的结果（availableActions 由 TASK-086 统一提供）。 */
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
     * 详情阶段版本（08 TASK-020）：只含已实现字段；currentAssessment、remediation、recovery、availableActions
     * 在 TASK-085/086 加入，不返回占位值。
     */
    record IncidentDetailResponse(
            String incidentKey,
            String title,
            String description,
            SystemRef system,
            IncidentStatus status,
            String statusLabel,
            long version,
            Impact impact,
            String resolvedAt,
            List<AffectedResource> affectedResources,
            Investigation investigation) {

        record SystemRef(String systemKey, String name) {}

        record Impact(String summary, String startedAt, String detectedAt) {}

        record AffectedResource(String resourceKey, String name, ResourceType resourceType) {}

        record Investigation(int runNo, boolean stopRequested) {}

        static IncidentDetailResponse of(IncidentDetailView view) {
            return new IncidentDetailResponse(
                    view.incidentKey(),
                    view.title(),
                    view.description(),
                    new SystemRef(view.systemKey(), view.systemName()),
                    view.status(),
                    statusLabel(view.status()),
                    view.version(),
                    new Impact(
                            view.impactSummary(),
                            ApiTimes.format(view.startedAt()),
                            ApiTimes.format(view.detectedAt())),
                    ApiTimes.format(view.resolvedAt()),
                    view.affectedResources().stream()
                            .map(IncidentDetailResponse::affected)
                            .toList(),
                    view.investigation() == null
                            ? null
                            : new Investigation(
                                    view.investigation().runNo(),
                                    view.investigation().stopRequested()));
        }

        private static AffectedResource affected(AffectedResourceView view) {
            return new AffectedResource(view.resourceKey(), view.name(), view.resourceType());
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
