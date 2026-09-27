package io.github.ismoyuan.opspilot.domain.hypothesis;

/**
 * 待验证原因的当前判断（01 §13～§14、04 §27）。不是永久终态：新证据可以改变判断，每次变化都要追加时间线。
 */
public enum HypothesisStatus {
    /** 刚提出，尚未验证；只作为初始状态。 */
    PENDING,
    SUPPORTED,
    INSUFFICIENT_EVIDENCE,
    REFUTED;

    /**
     * 合法的状态变化：目标必须不同于当前状态，且不能回到 PENDING（已评估过的原因不再是“尚未验证”，
     * 判断不了用 INSUFFICIENT_EVIDENCE 表达）。其余三种已评估状态之间可以互相变化（01 §14）。
     */
    public boolean canChangeTo(HypothesisStatus target) {
        return target != this && target != PENDING;
    }
}
