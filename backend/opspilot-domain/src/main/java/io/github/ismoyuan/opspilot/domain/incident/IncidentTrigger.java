package io.github.ismoyuan.opspilot.domain.incident;

/**
 * 引起 Incident 状态迁移的业务事件，对应 01 §4 主状态机的每条边。
 * 触发条件（审批、执行结果、恢复判定等）由各用例在调用前确认；本枚举只命名迁移。
 */
public enum IncidentTrigger {
    /** CREATED → INVESTIGATING。 */
    START_INVESTIGATION,
    /** DIAGNOSED → INVESTIGATING。 */
    CONTINUE_INVESTIGATION,
    /** 合法收束并产生 Diagnosis：INVESTIGATING → DIAGNOSED。 */
    COMPLETE_INVESTIGATION,
    /** 合法写方案并创建审批：DIAGNOSED → AWAITING_APPROVAL。 */
    REQUEST_APPROVAL,
    /** 用户外部处理后发起恢复验证：DIAGNOSED → VERIFYING。 */
    VERIFY_RECOVERY,
    /** AWAITING_APPROVAL → DIAGNOSED。 */
    REJECT_APPROVAL,
    /** AWAITING_APPROVAL → DIAGNOSED。 */
    CANCEL_APPROVAL,
    /** 批准且 Execution 准入：AWAITING_APPROVAL → EXECUTING。 */
    START_EXECUTION,
    /** 执行失败或结果不确定收束：EXECUTING → DIAGNOSED。 */
    EXECUTION_FAILED,
    /** EXECUTING → VERIFYING。 */
    EXECUTION_SUCCEEDED,
    /** 唯一通向 RESOLVED 的边（01 §31）：VERIFYING → RESOLVED。 */
    VERIFICATION_PASSED,
    /** 新 run、同一 Investigation：VERIFYING → INVESTIGATING。 */
    VERIFICATION_FAILED,
    /** VERIFYING → DIAGNOSED。 */
    VERIFICATION_INCONCLUSIVE,
    /** CREATED、INVESTIGATING、DIAGNOSED、AWAITING_APPROVAL → CANCELLED。 */
    CANCEL_INCIDENT
}
