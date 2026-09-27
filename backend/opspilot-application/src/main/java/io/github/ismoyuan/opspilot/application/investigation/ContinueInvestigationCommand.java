package io.github.ismoyuan.opspilot.application.investigation;

/** 继续调查（05 §28）；不接受客户端额度，本轮限制沿用首次快照。 */
public record ContinueInvestigationCommand(String incidentKey, long expectedVersion, String actor) {}
