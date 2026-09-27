package io.github.ismoyuan.opspilot.application.investigation;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.investigation.Investigation;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 已按 Incident → Investigation 顺序加锁、且 Incident 处于 INVESTIGATING 的调查工作空间。Hypothesis/Evidence 等调查事实
 * 在其中写入：Incident 行锁使同一 Incident 的时间线顺序与提交顺序一致（04 §57）。
 *
 * <p>只核对调查阶段；run 号、Stop 与 deadline 的准入由调查 Guard 负责（TASK-039～041）。
 */
public record ActiveInvestigation(Incident incident, Investigation investigation) {

    public ActiveInvestigation {
        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(investigation, "investigation");
    }

    /**
     * 必须在事务内调用。
     *
     * @throws ApplicationException Incident 不存在（INCIDENT_NOT_FOUND）或不在 INVESTIGATING（INCIDENT_STATE_CONFLICT）
     */
    public static ActiveInvestigation lock(
            IncidentRepository incidents, InvestigationRepository investigations, long incidentId) {
        Incident incident = incidents
                .findByIdForUpdate(incidentId)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.INCIDENT_NOT_FOUND, "Incident not found", Map.of("incidentId", incidentId)));
        if (incident.status() != IncidentStatus.INVESTIGATING) {
            throw new ApplicationException(
                    ErrorCode.INCIDENT_STATE_CONFLICT,
                    "Investigation facts require an active investigation",
                    Map.of(
                            "incidentKey", incident.incidentKey().value(),
                            "currentStatus", incident.status().name(),
                            "expectedStatuses", List.of(IncidentStatus.INVESTIGATING.name())));
        }
        Investigation investigation = investigations
                .findByIncidentIdForUpdate(incidentId)
                .orElseThrow(
                        () -> new IllegalStateException("INVESTIGATING incident without investigation: " + incidentId));
        return new ActiveInvestigation(incident, investigation);
    }

    public long investigationId() {
        return investigation.id();
    }

    public String incidentKey() {
        return incident.incidentKey().value();
    }
}
