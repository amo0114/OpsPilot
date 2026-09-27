package io.github.ismoyuan.opspilot.web.investigation;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.github.ismoyuan.opspilot.application.investigation.query.DiagnosisDetailView;
import io.github.ismoyuan.opspilot.application.investigation.query.DiagnosisSummaryView;
import io.github.ismoyuan.opspilot.application.investigation.query.EvidenceView;
import io.github.ismoyuan.opspilot.application.investigation.query.HypothesisView;
import io.github.ismoyuan.opspilot.application.investigation.query.InvestigationOverviewView;
import io.github.ismoyuan.opspilot.application.investigation.query.ObservationDetailView;
import io.github.ismoyuan.opspilot.application.investigation.query.ObservationSummaryView;
import io.github.ismoyuan.opspilot.application.investigation.query.ResourceRefView;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import io.github.ismoyuan.opspilot.web.response.ApiTimes;
import java.util.List;

/** 调查技术详情响应 DTO（05 §50～§56）；与领域对象、数据库行分开（07 §28）。不暴露内部 Incident/Investigation id。 */
final class InvestigationResponses {

    private InvestigationResponses() {}

    record OverviewResponse(
            int runNo,
            String startedAt,
            String currentRunStartedAt,
            String lastActivityAt,
            boolean stopRequested,
            Budget budget,
            long totalCapabilityCalls,
            long hypothesisCount,
            long observationCount,
            long evidenceCount,
            long diagnosisVersions) {

        /** 本轮额度（05 §50）：不是调查进度。 */
        record Budget(
                String scope,
                int capabilityCallsUsed,
                int capabilityCallsLimit,
                int remainingCapabilityCalls,
                long durationSeconds,
                int durationLimitSeconds) {}

        static OverviewResponse of(InvestigationOverviewView view) {
            var budget = view.budget();
            return new OverviewResponse(
                    view.runNo(),
                    ApiTimes.format(view.startedAt()),
                    ApiTimes.format(view.currentRunStartedAt()),
                    ApiTimes.format(view.lastActivityAt()),
                    view.stopRequested(),
                    new Budget(
                            "ACTIVE_RUN",
                            budget.capabilityCallsUsed(),
                            budget.capabilityCallsLimit(),
                            budget.remainingCapabilityCalls(),
                            budget.durationSeconds(),
                            budget.durationLimitSeconds()),
                    view.totalCapabilityCalls(),
                    view.hypothesisCount(),
                    view.observationCount(),
                    view.evidenceCount(),
                    view.diagnosisVersions());
        }
    }

    record HypothesisResponse(long id, String title, String description, HypothesisStatus status) {

        static HypothesisResponse of(HypothesisView view) {
            return new HypothesisResponse(view.id(), view.title(), view.description(), view.status());
        }
    }

    record ResourceRef(String resourceKey, String name) {

        static ResourceRef of(ResourceRefView view) {
            return new ResourceRef(view.resourceKey(), view.name());
        }
    }

    /** 05 §52：不含 payload 与原始结果。 */
    record ObservationSummaryResponse(
            long id,
            ObservationKind kind,
            ResourceRef resource,
            String summary,
            String observedAt,
            long capabilityInvocationId) {

        static ObservationSummaryResponse of(ObservationSummaryView view) {
            return new ObservationSummaryResponse(
                    view.id(),
                    view.kind(),
                    ResourceRef.of(view.resource()),
                    view.summary(),
                    ApiTimes.format(view.observedAt()),
                    view.capabilityInvocationId());
        }
    }

    /**
     * 05 §53：schema、结构化载荷、来源窗口与来源调用。
     *
     * @param payload 原样输出已存储（写入前已脱敏）的 JSON 对象，不再转义为字符串
     * @param window 无来源窗口时为空
     */
    record ObservationDetailResponse(
            long id,
            ObservationKind kind,
            ResourceRef resource,
            String summary,
            String observedAt,
            Schema schema,
            @JsonRawValue String payload,
            Window window,
            Invocation invocation) {

        record Schema(String name, int version) {}

        record Window(String start, String end) {}

        record Invocation(long id, String capabilityKey, int runNo) {}

        static ObservationDetailResponse of(ObservationDetailView view) {
            return new ObservationDetailResponse(
                    view.id(),
                    view.kind(),
                    ResourceRef.of(view.resource()),
                    view.summary(),
                    ApiTimes.format(view.observedAt()),
                    new Schema(view.schemaName(), view.schemaVersion()),
                    view.payload(),
                    view.windowStart() == null
                            ? null
                            : new Window(ApiTimes.format(view.windowStart()), ApiTimes.format(view.windowEnd())),
                    new Invocation(view.capabilityInvocationId(), view.capabilityKey(), view.runNo()));
        }
    }

    /** 05 §54：没有版本或更新字段。 */
    record EvidenceResponse(
            long id, EvidenceRelation relation, String reason, ObservationRef observation, HypothesisRef hypothesis) {

        record ObservationRef(long id, String summary) {}

        record HypothesisRef(long id, String title) {}

        static EvidenceResponse of(EvidenceView view) {
            return new EvidenceResponse(
                    view.id(),
                    view.relation(),
                    view.reason(),
                    new ObservationRef(view.observationId(), view.observationSummary()),
                    new HypothesisRef(view.hypothesisId(), view.hypothesisTitle()));
        }
    }

    record DiagnosisSummaryResponse(
            int version, DiagnosisConclusionType conclusionType, String summary, String createdAt) {

        static DiagnosisSummaryResponse of(DiagnosisSummaryView view) {
            return new DiagnosisSummaryResponse(
                    view.version(), view.conclusionType(), view.summary(), ApiTimes.format(view.createdAt()));
        }
    }

    /**
     * 05 §56：evidenceIds 与 evidence 均来自创建时冻结的引用。
     *
     * @param primaryHypothesis UNDETERMINED 无主假设时为空
     */
    record DiagnosisDetailResponse(
            int version,
            int runNo,
            DiagnosisConclusionType conclusionType,
            EvidenceResponse.HypothesisRef primaryHypothesis,
            String summary,
            String impactSummary,
            TerminationReason terminationReason,
            String createdAt,
            List<Long> evidenceIds,
            List<EvidenceResponse> evidence) {

        static DiagnosisDetailResponse of(DiagnosisDetailView view) {
            return new DiagnosisDetailResponse(
                    view.version(),
                    view.runNo(),
                    view.conclusionType(),
                    view.primaryHypothesisId() == null
                            ? null
                            : new EvidenceResponse.HypothesisRef(
                                    view.primaryHypothesisId(), view.primaryHypothesisTitle()),
                    view.summary(),
                    view.impactSummary(),
                    view.terminationReason(),
                    ApiTimes.format(view.createdAt()),
                    view.evidence().stream().map(EvidenceView::id).toList(),
                    view.evidence().stream().map(EvidenceResponse::of).toList());
        }
    }
}
