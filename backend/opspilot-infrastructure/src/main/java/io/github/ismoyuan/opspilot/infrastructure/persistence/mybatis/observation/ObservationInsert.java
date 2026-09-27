package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.observation;

import java.time.LocalDateTime;

/** 插入参数；id 由 MyBatis 回填自增主键，未插入时保持为空。 */
final class ObservationInsert {

    private Long id;
    private final long incidentId;
    private final Long investigationId;
    private final Long recoveryVerificationId;
    private final long capabilityInvocationId;
    private final long managedResourceId;
    private final String observationKind;
    private final String schemaName;
    private final int schemaVersion;
    private final String payload;
    private final String summary;
    private final LocalDateTime observedAt;
    private final LocalDateTime windowStart;
    private final LocalDateTime windowEnd;
    private final LocalDateTime createdAt;

    ObservationInsert(
            long incidentId,
            Long investigationId,
            Long recoveryVerificationId,
            long capabilityInvocationId,
            long managedResourceId,
            String observationKind,
            String schemaName,
            int schemaVersion,
            String payload,
            String summary,
            LocalDateTime observedAt,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            LocalDateTime createdAt) {
        this.incidentId = incidentId;
        this.investigationId = investigationId;
        this.recoveryVerificationId = recoveryVerificationId;
        this.capabilityInvocationId = capabilityInvocationId;
        this.managedResourceId = managedResourceId;
        this.observationKind = observationKind;
        this.schemaName = schemaName;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
        this.summary = summary;
        this.observedAt = observedAt;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getIncidentId() {
        return incidentId;
    }

    public Long getInvestigationId() {
        return investigationId;
    }

    public Long getRecoveryVerificationId() {
        return recoveryVerificationId;
    }

    public long getCapabilityInvocationId() {
        return capabilityInvocationId;
    }

    public long getManagedResourceId() {
        return managedResourceId;
    }

    public String getObservationKind() {
        return observationKind;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getPayload() {
        return payload;
    }

    public String getSummary() {
        return summary;
    }

    public LocalDateTime getObservedAt() {
        return observedAt;
    }

    public LocalDateTime getWindowStart() {
        return windowStart;
    }

    public LocalDateTime getWindowEnd() {
        return windowEnd;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
