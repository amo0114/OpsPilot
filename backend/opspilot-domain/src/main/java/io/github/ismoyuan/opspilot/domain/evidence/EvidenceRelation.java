package io.github.ismoyuan.opspilot.domain.evidence;

/** 观测与待验证原因之间的关系（01 §17.1、04 §29）。 */
public enum EvidenceRelation {
    SUPPORTS,
    REFUTES,
    /** 提供背景信息，但不足以直接支持或反驳。 */
    CONTEXT
}
