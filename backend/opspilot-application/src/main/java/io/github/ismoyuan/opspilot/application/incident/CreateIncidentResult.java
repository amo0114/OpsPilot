package io.github.ismoyuan.opspilot.application.incident;

import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;

/** 创建结果（05 §20）；availableActions 由 TASK-086 统一计算。 */
public record CreateIncidentResult(IncidentKey incidentKey, IncidentStatus status, long version) {}
