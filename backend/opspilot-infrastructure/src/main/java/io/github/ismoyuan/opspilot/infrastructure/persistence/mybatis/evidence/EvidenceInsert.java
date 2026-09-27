package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.evidence;

import java.time.LocalDateTime;

/** 插入参数；id 由 MyBatis 回填自增主键。 */
final class EvidenceInsert {

    private Long id;
    private final long investigationId;
    private final long observationId;
    private final long hypothesisId;
    private final String relation;
    private final String reason;
    private final LocalDateTime createdAt;

    EvidenceInsert(
            long investigationId,
            long observationId,
            long hypothesisId,
            String relation,
            String reason,
            LocalDateTime createdAt) {
        this.investigationId = investigationId;
        this.observationId = observationId;
        this.hypothesisId = hypothesisId;
        this.relation = relation;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getInvestigationId() {
        return investigationId;
    }

    public long getObservationId() {
        return observationId;
    }

    public long getHypothesisId() {
        return hypothesisId;
    }

    public String getRelation() {
        return relation;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
