package io.github.ismoyuan.opspilot.domain.timeline;

/** 时间线事件类型（01 §35）；随各 Task 实际写入的事件逐个加入，不预先铺满。 */
public enum TimelineEventType {
    INCIDENT_CREATED,
    /** Start、Continue 等进入新一轮调查（01 §9），载荷记录来源与轮号。 */
    INVESTIGATION_STARTED
}
