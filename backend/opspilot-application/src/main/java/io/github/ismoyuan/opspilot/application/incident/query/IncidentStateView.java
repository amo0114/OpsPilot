package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.domain.incident.IncidentAction;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.util.List;

/** SSE incident-state 的内容（05 §64）：当前状态、版本与 Java 计算的 availableActions。 */
public record IncidentStateView(
        String incidentKey, IncidentStatus status, long version, List<IncidentAction> availableActions) {

    public IncidentStateView {
        availableActions = List.copyOf(availableActions);
    }
}
