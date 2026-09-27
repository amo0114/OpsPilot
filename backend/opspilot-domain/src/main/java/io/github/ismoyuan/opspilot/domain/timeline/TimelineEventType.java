package io.github.ismoyuan.opspilot.domain.timeline;

/** 时间线事件类型（01 §35）；随各 Task 实际写入的事件逐个加入，不预先铺满。 */
public enum TimelineEventType {
    INCIDENT_CREATED,
    /** Start、Continue 等进入新一轮调查（01 §9），载荷记录来源与轮号。 */
    INVESTIGATION_STARTED,
    /** 当前 run 的协作式停止意图已落账（05 §27），状态仍为 INVESTIGATING。 */
    INVESTIGATION_STOP_REQUESTED,
    INCIDENT_CANCELLED,
    HYPOTHESIS_CREATED,
    /** Hypothesis 当前状态的每一次变化（01 §14），历史只在时间线保存。 */
    HYPOTHESIS_STATUS_CHANGED,
    EVIDENCE_LINKED,
    /** 新 Diagnosis 版本已冻结，Incident 同事务 INVESTIGATING → DIAGNOSED。 */
    DIAGNOSIS_CREATED
}
