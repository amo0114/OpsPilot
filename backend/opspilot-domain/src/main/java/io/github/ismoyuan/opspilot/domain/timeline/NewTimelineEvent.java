package io.github.ismoyuan.opspilot.domain.timeline;

import java.time.Instant;
import java.util.Objects;

/**
 * 待追加的时间线事件（04 §55）。时间线只追加、不修改（01 §34、INV-008）。
 *
 * @param summary 面向用户的可读描述（05 §61），上限与 V002 一致
 * @param actorId 可为空
 * @param correlationId 可为空
 */
public record NewTimelineEvent(
        long incidentId,
        TimelineEventType eventType,
        Instant occurredAt,
        TimelineActorType actorType,
        String actorId,
        String summary,
        TimelinePayload payload,
        String correlationId) {

    public static final int SUMMARY_MAX = 1000;
    public static final int ACTOR_ID_MAX = 128;
    public static final int CORRELATION_ID_MAX = 64;

    public NewTimelineEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(payload, "payload");
        if (summary.isBlank() || summary.codePointCount(0, summary.length()) > SUMMARY_MAX) {
            throw new IllegalArgumentException("summary must be non-blank and at most " + SUMMARY_MAX);
        }
        if (actorId != null && (actorId.isBlank() || actorId.length() > ACTOR_ID_MAX)) {
            throw new IllegalArgumentException("invalid actorId");
        }
        if (correlationId != null && correlationId.length() > CORRELATION_ID_MAX) {
            throw new IllegalArgumentException("correlationId too long");
        }
    }
}
