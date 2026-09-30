package io.github.ismoyuan.opspilot.application.recovery;

/**
 * 单个 Criterion 的三值结果（06 §116、04 §80）。FALSE 只能来自有效、未过期且能决定谓词的真实样本；Provider 失败、字段缺失、
 * 值为空、样本不足或过期都是 UNKNOWN，不能伪造成 FALSE 或 TRUE。合取优先级 FALSE > UNKNOWN > TRUE（求值属 TASK-077）。
 */
public enum CriterionResult {
    TRUE,
    FALSE,
    UNKNOWN
}
