package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import java.time.Instant;
import java.util.List;

/**
 * Java → AI Runtime 的单步调查请求（05 §77、02 §15）。investigationId、runNo、stepId 由 Java 生成并在请求前持久化，
 * AI Runtime 只能回显。内容由 Context Builder（TASK-037）挑选：不得含 Ground Truth、控制日志、凭证或任意业务 payload。
 *
 * @param currentDiagnosis 该 Investigation 最新的冻结 Diagnosis，没有时为空
 */
public record InvestigationStepRequest(
        int protocolVersion,
        long investigationId,
        int runNo,
        long stepId,
        String correlationId,
        Incident incident,
        List<AffectedResource> affectedResources,
        List<Hypothesis> hypotheses,
        List<Observation> observations,
        List<Evidence> evidence,
        CurrentDiagnosis currentDiagnosis,
        List<CapabilityDescriptor> availableCapabilities,
        Budget budget,
        List<TimelineEntry> recentTimeline) {

    public InvestigationStepRequest {
        ProtocolChecks.protocolVersion(protocolVersion);
        ProtocolChecks.id("investigationId", investigationId);
        ProtocolChecks.positive("runNo", runNo);
        ProtocolChecks.id("stepId", stepId);
        ProtocolChecks.correlationId(correlationId);
        ProtocolChecks.required("incident", incident);
        affectedResources = ProtocolChecks.list("affectedResources", affectedResources);
        hypotheses = ProtocolChecks.list("hypotheses", hypotheses);
        observations = ProtocolChecks.list("observations", observations);
        evidence = ProtocolChecks.list("evidence", evidence);
        availableCapabilities = ProtocolChecks.list("availableCapabilities", availableCapabilities);
        ProtocolChecks.required("budget", budget);
        recentTimeline = ProtocolChecks.list("recentTimeline", recentTimeline);
    }

    public record Incident(String incidentKey, String title, String impactSummary, Instant startedAt) {

        public Incident {
            ProtocolChecks.incidentKey(incidentKey);
            ProtocolChecks.text("title", title, 200);
            ProtocolChecks.text("impactSummary", impactSummary, 1000);
            ProtocolChecks.required("startedAt", startedAt);
        }
    }

    public record AffectedResource(long resourceId, String resourceKey, ResourceType resourceType) {

        public AffectedResource {
            ProtocolChecks.id("resourceId", resourceId);
            ProtocolChecks.resourceKey(resourceKey);
            ProtocolChecks.required("resourceType", resourceType);
        }
    }

    /** @param description 可为空 */
    public record Hypothesis(long id, String title, String description, HypothesisStatus status) {

        public Hypothesis {
            ProtocolChecks.id("id", id);
            ProtocolChecks.text("title", title, 200);
            ProtocolChecks.optionalText("description", description, 2000);
            ProtocolChecks.required("status", status);
        }
    }

    /** @param runNo 来源调用所属 run；小于请求 runNo 即历史观测 */
    public record Observation(
            long id, int runNo, String resourceKey, ObservationKind kind, String summary, Instant observedAt) {

        public Observation {
            ProtocolChecks.id("id", id);
            ProtocolChecks.positive("runNo", runNo);
            ProtocolChecks.resourceKey(resourceKey);
            ProtocolChecks.required("kind", kind);
            ProtocolChecks.text("summary", summary, 1000);
            ProtocolChecks.required("observedAt", observedAt);
        }
    }

    public record Evidence(long id, long observationId, long hypothesisId, EvidenceRelation relation, String reason) {

        public Evidence {
            ProtocolChecks.id("id", id);
            ProtocolChecks.id("observationId", observationId);
            ProtocolChecks.id("hypothesisId", hypothesisId);
            ProtocolChecks.required("relation", relation);
            ProtocolChecks.text("reason", reason, 1000);
        }
    }

    /** @param primaryHypothesisId 可为空 */
    public record CurrentDiagnosis(
            int version,
            int runNo,
            DiagnosisConclusionType conclusionType,
            Long primaryHypothesisId,
            String summary,
            List<Long> evidenceIds) {

        public CurrentDiagnosis {
            ProtocolChecks.positive("version", version);
            ProtocolChecks.positive("runNo", runNo);
            ProtocolChecks.required("conclusionType", conclusionType);
            if (primaryHypothesisId != null) {
                ProtocolChecks.id("primaryHypothesisId", primaryHypothesisId);
            }
            ProtocolChecks.text("summary", summary, 2000);
            evidenceIds = ProtocolChecks.uniqueList("evidenceIds", evidenceIds, 0, Integer.MAX_VALUE);
            evidenceIds.forEach(id -> ProtocolChecks.id("evidenceIds", id));
        }
    }

    /** 本轮额度（05 §50 口径）：scope 固定 ACTIVE_RUN，计数都可为 0。 */
    public record Budget(
            BudgetScope scope,
            Integer capabilityCallsUsed,
            Integer capabilityCallsLimit,
            Integer remainingCapabilityCalls,
            Integer elapsedSeconds,
            Integer durationLimitSeconds) {

        public Budget {
            ProtocolChecks.required("scope", scope);
            ProtocolChecks.count("capabilityCallsUsed", capabilityCallsUsed);
            ProtocolChecks.count("capabilityCallsLimit", capabilityCallsLimit);
            ProtocolChecks.count("remainingCapabilityCalls", remainingCapabilityCalls);
            ProtocolChecks.count("elapsedSeconds", elapsedSeconds);
            ProtocolChecks.count("durationLimitSeconds", durationLimitSeconds);
        }
    }

    public enum BudgetScope {
        ACTIVE_RUN
    }

    public record TimelineEntry(String eventType, Instant occurredAt, String summary) {

        public TimelineEntry {
            ProtocolChecks.eventType(eventType);
            ProtocolChecks.required("occurredAt", occurredAt);
            ProtocolChecks.text("summary", summary, 1000);
        }
    }
}
