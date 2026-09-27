package io.github.ismoyuan.opspilot.application.investigation;

/** 请求停止当前 run（05 §27）；协作式，不等待在途工作结束。 */
public record StopInvestigationCommand(String incidentKey, long expectedVersion, String actor) {}
