package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.timeline;

import java.time.LocalDateTime;

/** incident_timeline_event 插入参数；id 由 MyBatis 回填自增主键。 */
final class TimelineEventInsert {

    private Long id;
    private final long incidentId;
    private final String eventType;
    private final LocalDateTime occurredAt;
    private final String actorType;
    private final String actorId;
    private final String summary;
    private final String payload;
    private final String correlationId;
    private final LocalDateTime createdAt;

    TimelineEventInsert(
            long incidentId,
            String eventType,
            LocalDateTime occurredAt,
            String actorType,
            String actorId,
            String summary,
            String payload,
            String correlationId,
            LocalDateTime createdAt) {
        this.incidentId = incidentId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.actorType = actorType;
        this.actorId = actorId;
        this.summary = summary;
        this.payload = payload;
        this.correlationId = correlationId;
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

    public String getEventType() {
        return eventType;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getSummary() {
        return summary;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
