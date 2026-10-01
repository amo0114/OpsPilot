package io.github.ismoyuan.opspilot.application.faultlab;

import io.github.ismoyuan.opspilot.domain.faultlab.FaultExperimentStatus;

/** 05 §72：实验环境已恢复；不表示 Incident 已恢复。 */
public record ResetFaultResult(long experimentId, FaultExperimentStatus status) {}
