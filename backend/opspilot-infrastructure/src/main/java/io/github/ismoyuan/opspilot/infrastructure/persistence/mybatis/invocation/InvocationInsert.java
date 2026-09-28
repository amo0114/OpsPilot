package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.invocation;

import java.time.LocalDateTime;

/** 登记调查调用的参数；id 由 MyBatis 回填。 */
final class InvocationInsert {

    private Long id;
    private final long incidentId;
    private final long investigationId;
    private final int runNo;
    private final String capabilityKey;
    private final long managedResourceId;
    private final String requestSchemaName;
    private final int requestSchemaVersion;
    private final String requestPayload;
    private final LocalDateTime startedAt;
    private final String correlationId;

    InvocationInsert(
            long incidentId,
            long investigationId,
            int runNo,
            String capabilityKey,
            long managedResourceId,
            String requestSchemaName,
            int requestSchemaVersion,
            String requestPayload,
            LocalDateTime startedAt,
            String correlationId) {
        this.incidentId = incidentId;
        this.investigationId = investigationId;
        this.runNo = runNo;
        this.capabilityKey = capabilityKey;
        this.managedResourceId = managedResourceId;
        this.requestSchemaName = requestSchemaName;
        this.requestSchemaVersion = requestSchemaVersion;
        this.requestPayload = requestPayload;
        this.startedAt = startedAt;
        this.correlationId = correlationId;
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

    public long getInvestigationId() {
        return investigationId;
    }

    public int getRunNo() {
        return runNo;
    }

    public String getCapabilityKey() {
        return capabilityKey;
    }

    public long getManagedResourceId() {
        return managedResourceId;
    }

    public String getRequestSchemaName() {
        return requestSchemaName;
    }

    public int getRequestSchemaVersion() {
        return requestSchemaVersion;
    }

    public String getRequestPayload() {
        return requestPayload;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
