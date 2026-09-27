package io.github.ismoyuan.opspilot.application.investigation;

/** 开始调查（05 §24）。 */
public record StartInvestigationCommand(String incidentKey, long expectedVersion, String actor) {}
