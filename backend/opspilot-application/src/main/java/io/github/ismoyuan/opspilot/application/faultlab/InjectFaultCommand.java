package io.github.ismoyuan.opspilot.application.faultlab;

/** 05 §70：在某个业务系统上注入一个场景的故障；actor 记入 Incident 的创建者。 */
public record InjectFaultCommand(String scenarioKey, String systemKey, String actor) {}
