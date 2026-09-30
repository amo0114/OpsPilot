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
    /** 一次 OBSERVE 调用已通过准入并登记（04 §72 事务一）；来源于 AI 的调查请求时发起方为 AI_RUNTIME。 */
    CAPABILITY_INVOKED,
    /** 调用失败（04 §72 事务二），载荷含 06 §35 错误码；不产生 Observation。 */
    CAPABILITY_FAILED,
    /** 调用成功产生的一条 Observation（每条一个事件，摘要即 Observation 摘要，05 §60）。 */
    OBSERVATION_RECORDED,
    /**
     * AI 的能力请求被准入 Guard 拒绝（如重复、参数不在受控域、未绑定）：不建调用、不扣预算，作为给当前 run 的结构化反馈（06 §124），
     * 01 §35 最低清单之外的补充类型。
     */
    CAPABILITY_REQUEST_REJECTED,
    /** 新 Diagnosis 版本已冻结，Incident 同事务 INVESTIGATING → DIAGNOSED。 */
    DIAGNOSIS_CREATED,
    /** AI 处理建议经 Java 校验形成 Plan / Action（01 §35）。 */
    REMEDIATION_PROPOSED,
    /** 为 Action 创建 PENDING Approval，Incident 同事务 DIAGNOSED → AWAITING_APPROVAL（01 §35、04 §77）。 */
    APPROVAL_REQUESTED,
    /** Approval 被批准：同事务创建 PENDING Execution 并冻结恢复合同，Incident AWAITING_APPROVAL → EXECUTING（04 §78）。 */
    APPROVAL_APPROVED,
    /** Execution 条件更新 PENDING → RUNNING 成功，即将发出唯一一次 CHANGE（04 §79）。 */
    ACTION_EXECUTION_STARTED,
    /**
     * 结果未知的 Execution 已登记一次只读核对（04 §82：每次核对计入 Timeline），登记提交后才发出 inspect；01 §35 最低清单之外的补充类型。
     */
    ACTION_EXECUTION_RECONCILIATION_ATTEMPTED,
    /** 重启操作确定成功或经只读核对确认已生效（只表示操作成功，不表示已恢复，06 §109）。 */
    ACTION_EXECUTION_SUCCEEDED,
    /**
     * Execution 失败：准入前（未发出 CHANGE）、执行中明确失败，或核对耗尽/到期仍无法确认（EXECUTION_RESULT_UNCERTAIN），Incident 回到
     * DIAGNOSED（04 §79、§82）。
     */
    ACTION_EXECUTION_FAILED,
    /**
     * 用户在外部处理后请求恢复验证（05 §34）：同事务创建 PENDING Verification 并 DIAGNOSED → VERIFYING；01 §35 最低清单之外的补充类型。
     */
    RECOVERY_VERIFICATION_REQUESTED,
    /** RecoveryVerification PENDING → RUNNING，开始按冻结快照采样（01 §35、04 §80）。 */
    RECOVERY_VERIFICATION_STARTED,
    /** 全部 required 检查 TRUE（01 §30）；Incident 的相应迁移属 TASK-082。 */
    RECOVERY_VERIFICATION_PASSED,
    /** 至少一项 required 检查明确 FALSE。 */
    RECOVERY_VERIFICATION_FAILED,
    /** 没有 FALSE 但至少一项 required 检查 UNKNOWN。 */
    RECOVERY_VERIFICATION_INCONCLUSIVE,
    /** Approval 被拒绝，Incident 回到 DIAGNOSED（01 §24.2）。 */
    APPROVAL_REJECTED,
    /** Approval 被撤回（05 §42），Incident 回到 DIAGNOSED；01 §35 最低清单之外的补充类型。 */
    APPROVAL_CANCELLED
}
