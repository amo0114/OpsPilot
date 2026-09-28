package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.diagnosis;

import java.time.LocalDateTime;

/** 插入参数；id 由 MyBatis 回填自增主键，版本号在 SQL 内分配。 */
final class DiagnosisInsert {

    private Long id;
    private final long investigationId;
    private final int runNo;
    private final int versionNo;
    private final String conclusionType;
    private final Long primaryHypothesisId;
    private final String summary;
    private final String impactSummary;
    private final String terminationReason;
    private final LocalDateTime createdAt;

    DiagnosisInsert(
            long investigationId,
            int runNo,
            int versionNo,
            String conclusionType,
            Long primaryHypothesisId,
            String summary,
            String impactSummary,
            String terminationReason,
            LocalDateTime createdAt) {
        this.investigationId = investigationId;
        this.runNo = runNo;
        this.versionNo = versionNo;
        this.conclusionType = conclusionType;
        this.primaryHypothesisId = primaryHypothesisId;
        this.summary = summary;
        this.impactSummary = impactSummary;
        this.terminationReason = terminationReason;
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

    public int getRunNo() {
        return runNo;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getConclusionType() {
        return conclusionType;
    }

    public Long getPrimaryHypothesisId() {
        return primaryHypothesisId;
    }

    public String getSummary() {
        return summary;
    }

    public String getImpactSummary() {
        return impactSummary;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
