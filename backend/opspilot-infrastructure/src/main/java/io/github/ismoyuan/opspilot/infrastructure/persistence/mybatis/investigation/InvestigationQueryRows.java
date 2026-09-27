package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import java.time.LocalDateTime;

/** 调查技术详情查询的扁平行；时间为 UTC。 */
final class InvestigationQueryRows {

    private InvestigationQueryRows() {}

    record ScopeRow(long incidentId, String status, LocalDateTime updatedAt, Long investigationId) {}

    record FactsRow(
            int runNo,
            LocalDateTime startedAt,
            LocalDateTime currentRunStartedAt,
            LocalDateTime lastActivityAt,
            LocalDateTime stopRequestedAt,
            int currentRunCapabilityCount,
            int maxCapabilityCalls,
            int maxDurationSeconds,
            long totalCapabilityCalls,
            long hypothesisCount,
            long observationCount,
            long evidenceCount,
            long diagnosisVersions,
            LocalDateTime currentRunDiagnosedAt) {}

    record ObservationRow(
            long id,
            String kind,
            String resourceKey,
            String resourceName,
            String summary,
            LocalDateTime observedAt,
            long capabilityInvocationId,
            String schemaName,
            int schemaVersion,
            String payload,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            String capabilityKey,
            int runNo) {}

    record DiagnosisSummaryRow(int version, String conclusionType, String summary, LocalDateTime createdAt) {}

    record DiagnosisRow(
            int version,
            int runNo,
            String conclusionType,
            Long primaryHypothesisId,
            String primaryHypothesisTitle,
            String summary,
            String impactSummary,
            String terminationReason,
            LocalDateTime createdAt,
            long id) {}
}
