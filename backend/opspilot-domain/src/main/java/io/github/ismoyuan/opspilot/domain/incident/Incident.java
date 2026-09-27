package io.github.ismoyuan.opspilot.domain.incident;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 已持久化的故障（03 §16、04 §13）。状态只能经 {@link #transitionFor} 产生的迁移、由仓储条件更新改变。
 *
 * @param description 可为空
 * @param resolvedAt 当且仅当 RESOLVED 时非空（01 §31）
 * @param version 对应 lock_version，API 对外称 version（05 §11）
 */
public record Incident(
        long id,
        IncidentKey incidentKey,
        long managedSystemId,
        String title,
        String description,
        String impactSummary,
        IncidentStatus status,
        IncidentSource createdSource,
        String createdBy,
        Instant startedAt,
        Instant detectedAt,
        Instant resolvedAt,
        long version) {

    public Incident {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(impactSummary, "impactSummary");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdSource, "createdSource");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if ((status == IncidentStatus.RESOLVED) != (resolvedAt != null)) {
            throw new IllegalArgumentException("resolvedAt must be present exactly when RESOLVED");
        }
    }

    /**
     * 按当前状态与版本生成迁移；不允许时抛 INCIDENT_STATE_CONFLICT，details 为 05 §9 结构。
     */
    public IncidentTransition transitionFor(IncidentTrigger trigger) {
        IncidentStatus target = IncidentTransitionPolicy.target(status, trigger)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.INCIDENT_STATE_CONFLICT,
                        "Incident transition not allowed: " + status + " --" + trigger + "-->",
                        Map.of(
                                "incidentKey",
                                incidentKey.value(),
                                "currentStatus",
                                status.name(),
                                "expectedStatuses",
                                IncidentTransitionPolicy.allowedSources(trigger).stream()
                                        .sorted()
                                        .map(Enum::name)
                                        .toList())));
        return new IncidentTransition(id, trigger, status, version, target);
    }

    /** 同上，但先核对调用方持有的版本（05 §11 expectedVersion）；版本不符抛 INCIDENT_VERSION_CONFLICT。 */
    public IncidentTransition transitionFor(IncidentTrigger trigger, long expectedVersion) {
        if (expectedVersion != version) {
            throw new DomainException(
                    ErrorCode.INCIDENT_VERSION_CONFLICT,
                    "Incident version mismatch",
                    Map.of("incidentKey", incidentKey.value(), "currentStatus", status.name(), "version", version));
        }
        return transitionFor(trigger);
    }
}
