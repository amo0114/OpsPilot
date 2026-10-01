package io.github.ismoyuan.opspilot.application.faultlab;

/** 注入器作用的对象：实验、所属系统与目标资源（不含 Ground Truth）。 */
public record FaultTarget(
        long experimentId, String scenarioKey, String systemKey, long targetResourceId, String targetResourceKey) {}
