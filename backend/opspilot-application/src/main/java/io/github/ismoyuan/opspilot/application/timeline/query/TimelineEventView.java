package io.github.ismoyuan.opspilot.application.timeline.query;

import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Instant;

/**
 * 时间线中的一条已提交事件（05 §60～§61）：默认展示写入时生成的人类可读 summary；eventType 供技术详情区分，载荷不在默认页面返回。
 */
public record TimelineEventView(
        long id, TimelineEventType eventType, Instant occurredAt, TimelineActorType actorType, String summary) {}
