package io.github.ismoyuan.opspilot.application.incident;

import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;

public record CancelIncidentResult(IncidentKey incidentKey, IncidentStatus status, long version) {}
