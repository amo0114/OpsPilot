package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.agentstep;

import java.time.LocalDateTime;

/** 登记 RUNNING Step 的参数；id 由 MyBatis 回填。 */
final class AgentStepInsert {

    private Long id;
    private final long incidentId;
    private final long investigationId;
    private final int runNo;
    private final LocalDateTime startedAt;

    AgentStepInsert(long incidentId, long investigationId, int runNo, LocalDateTime startedAt) {
        this.incidentId = incidentId;
        this.investigationId = investigationId;
        this.runNo = runNo;
        this.startedAt = startedAt;
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

    public LocalDateTime getStartedAt() {
        return startedAt;
    }
}
