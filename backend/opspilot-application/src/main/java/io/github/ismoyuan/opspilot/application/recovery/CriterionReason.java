package io.github.ismoyuan.opspilot.application.recovery;

/**
 * 单项检查结果的原因（04 §50 result_payload：缺失／失败原因）。TRUE 只对应 SATISFIED，FALSE 只对应 VIOLATED；其余都是 UNKNOWN 的
 * 如实原因，不冒充已经完成采样。
 */
public enum CriterionReason {
    SATISFIED,
    VIOLATED,
    /** 某个样本调用失败或中断。 */
    SAMPLE_FAILED,
    /** 样本成功但所需字段未知（组缺失、值为空、状态未知）。 */
    VALUE_UNKNOWN,
    /** 样本超过 maxSampleAgeSeconds，不能支持 TRUE 或 FALSE。 */
    SAMPLE_EXPIRED,
    /**
     * 样本在 Verification 冻结的 deadline 之后才取得（04 §80、B28-R1），不能支持 TRUE 或 FALSE；调用与 Observation 仍按原调用审计保留。
     */
    SAMPLE_AFTER_DEADLINE,
    /** 相邻实际样本间隔超过 maxGapSeconds，不能凭不连续数据宣布通过。 */
    SAMPLE_GAP_EXCEEDED,
    /** 样本数不足（期限已到或未能准入）。 */
    INSUFFICIENT_SAMPLES,
    /** 运行时能力不可用（绑定停用、资源不再可用等），未能采样。 */
    NOT_ADMITTED,
    /** 已有 required 检查明确 FALSE，本项未执行（06 §116 允许的短路）。 */
    NOT_EXECUTED
}
