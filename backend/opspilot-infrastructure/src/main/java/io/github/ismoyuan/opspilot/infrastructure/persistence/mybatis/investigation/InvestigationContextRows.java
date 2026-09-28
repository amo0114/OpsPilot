package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import java.time.LocalDateTime;

/** 上下文查询的扁平行；时间为 UTC。 */
final class InvestigationContextRows {

    private InvestigationContextRows() {}

    record HeadRow(
            long incidentId,
            String incidentKey,
            String title,
            String impactSummary,
            LocalDateTime startedAt,
            String status,
            long investigationId,
            int currentRunNo,
            LocalDateTime currentRunStartedAt,
            int currentRunCapabilityCount,
            int maxCapabilityCalls,
            int maxDurationSeconds) {}

    record ResourceRow(long resourceId, String resourceKey, String resourceType) {}

    record HypothesisRow(long id, String title, String description, String status) {}

    record EvidenceRow(long id, long observationId, long hypothesisId, String relation, String reason) {}

    record ObservationRow(
            long id, int runNo, String resourceKey, String kind, String summary, LocalDateTime observedAt) {}

    record DiagnosisRow(
            long id, int version, int runNo, String conclusionType, Long primaryHypothesisId, String summary) {}

    record TimelineRow(String eventType, LocalDateTime occurredAt, String summary) {}
}
