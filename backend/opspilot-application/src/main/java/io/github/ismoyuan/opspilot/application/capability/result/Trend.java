package io.github.ismoyuan.opspilot.application.capability.result;

/** metrics.query 的趋势（06 §43），由 Java 按真实样本确定，不由 LLM 判断；样本不足为 UNKNOWN。 */
public enum Trend {
    INCREASING,
    STABLE,
    DECREASING,
    UNKNOWN
}
