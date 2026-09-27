package io.github.ismoyuan.opspilot.domain.timeline;

/** 时间线事件的发起方（04 §56）；AI_RUNTIME 表示来源于 AI 提议，写入者仍是 Java。 */
public enum TimelineActorType {
    USER,
    SYSTEM,
    AI_RUNTIME
}
