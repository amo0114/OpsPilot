package io.github.ismoyuan.opspilot.application.incident;

import io.github.ismoyuan.opspilot.domain.incident.IncidentAction;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.util.List;

/** 创建结果（05 §20），含 Java 按 {@code IncidentActionPolicy} 给出的 availableActions（08 TASK-086）。 */
public record CreateIncidentResult(
        IncidentKey incidentKey, IncidentStatus status, long version, List<IncidentAction> availableActions) {

    public CreateIncidentResult {
        availableActions = List.copyOf(availableActions);
    }
}
