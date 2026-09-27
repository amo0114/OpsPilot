package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.hypothesis;

import java.time.LocalDateTime;

/** 插入参数；id 由 MyBatis 回填自增主键。 */
final class HypothesisInsert {

    private Long id;
    private final long investigationId;
    private final String title;
    private final String description;
    private final LocalDateTime createdAt;

    HypothesisInsert(long investigationId, String title, String description, LocalDateTime createdAt) {
        this.investigationId = investigationId;
        this.title = title;
        this.description = description;
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

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
