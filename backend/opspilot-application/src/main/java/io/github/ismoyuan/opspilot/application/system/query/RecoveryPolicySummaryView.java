package io.github.ismoyuan.opspilot.application.system.query;

/** 组件当前恢复标准摘要（05 §17）。 */
public record RecoveryPolicySummaryView(String name, int version, String summary) {}
