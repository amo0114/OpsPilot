package io.github.ismoyuan.opspilot.application.hypothesis;

/**
 * 登记 AI 提出的待验证原因（05 §81）。
 *
 * @param description 可为空
 */
public record ProposeHypothesisCommand(long incidentId, String title, String description) {}
