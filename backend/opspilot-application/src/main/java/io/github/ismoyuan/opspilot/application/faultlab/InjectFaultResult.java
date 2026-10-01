package io.github.ismoyuan.opspilot.application.faultlab;

import io.github.ismoyuan.opspilot.application.incident.CreateIncidentResult;
import io.github.ismoyuan.opspilot.domain.faultlab.FaultExperimentStatus;

/** 05 §70：已确认生效的实验与同事务创建的 CREATED Incident（含 availableActions）；不含 Ground Truth。 */
public record InjectFaultResult(long experimentId, FaultExperimentStatus status, CreateIncidentResult incident) {}
