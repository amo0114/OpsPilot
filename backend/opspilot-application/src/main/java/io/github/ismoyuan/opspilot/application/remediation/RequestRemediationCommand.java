package io.github.ismoyuan.opspilot.application.remediation;

/** 05 §29 请求处理建议。 */
public record RequestRemediationCommand(String incidentKey, long expectedVersion, String actor) {}
