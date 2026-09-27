package io.github.ismoyuan.opspilot.domain.diagnosis;

/** 一次调查最终能得出多明确的结论（01 §20～§21、04 §34）；与 Hypothesis 状态是不同概念。 */
public enum DiagnosisConclusionType {
    PRIMARY_CAUSE_IDENTIFIED,
    POSSIBLE_CAUSE,
    /** 暂时无法确定原因：合法结果，不是失败（01 §12）；主假设可为空。 */
    UNDETERMINED;

    /** PRIMARY/POSSIBLE 必须有主假设及关联它的 SUPPORTS Evidence（01 §20）。 */
    public boolean requiresSupportedPrimaryHypothesis() {
        return this != UNDETERMINED;
    }
}
