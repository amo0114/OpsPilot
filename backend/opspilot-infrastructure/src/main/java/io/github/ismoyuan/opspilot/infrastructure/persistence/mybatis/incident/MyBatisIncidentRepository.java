package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.IncidentKeyTakenException;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentSource;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTransition;
import io.github.ismoyuan.opspilot.domain.incident.NewIncident;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisIncidentRepository implements IncidentRepository {

    private final IncidentMapper mapper;

    MyBatisIncidentRepository(IncidentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Incident> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisIncidentRepository::toDomain);
    }

    @Override
    public Optional<Incident> findByKey(IncidentKey incidentKey) {
        return Optional.ofNullable(mapper.selectByKey(incidentKey.value())).map(MyBatisIncidentRepository::toDomain);
    }

    @Override
    public Optional<Incident> findByKeyForUpdate(IncidentKey incidentKey) {
        return Optional.ofNullable(mapper.selectByKeyForUpdate(incidentKey.value()))
                .map(MyBatisIncidentRepository::toDomain);
    }

    @Override
    public Optional<Incident> findByIdForUpdate(long id) {
        return Optional.ofNullable(mapper.selectByIdForUpdate(id)).map(MyBatisIncidentRepository::toDomain);
    }

    @Override
    public Incident apply(IncidentTransition transition, Instant at) {
        LocalDateTime time = utc(at);
        int updated = mapper.transition(
                transition.incidentId(),
                transition.expectedStatus().name(),
                transition.expectedVersion(),
                transition.targetStatus().name(),
                transition.targetStatus() == IncidentStatus.RESOLVED ? time : null,
                time);
        if (updated == 1) {
            return toDomain(mapper.selectById(transition.incidentId()));
        }
        throw conflict(
                transition.incidentId(),
                null,
                transition.expectedStatus(),
                "transition " + transition.trigger(),
                mapper.selectByIdForShare(transition.incidentId()));
    }

    @Override
    public Incident incrementVersion(Incident current, Instant at) {
        int updated = mapper.incrementVersion(current.id(), current.status().name(), current.version(), utc(at));
        if (updated == 1) {
            return toDomain(mapper.selectById(current.id()));
        }
        throw conflict(
                current.id(),
                current.incidentKey().value(),
                current.status(),
                "version increment",
                mapper.selectByIdForShare(current.id()));
    }

    @Override
    public int lastSequenceOn(LocalDate day) {
        return mapper.selectLastSequence(IncidentKey.dayPrefix(day));
    }

    @Override
    public Incident insert(NewIncident incident, IncidentKey incidentKey, Instant createdAt) {
        try {
            mapper.insertCreated(
                    incidentKey.value(),
                    incident.managedSystemId(),
                    incident.title(),
                    incident.description(),
                    incident.impactSummary(),
                    incident.createdSource().name(),
                    incident.createdBy(),
                    utc(incident.startedAt()),
                    utc(incident.detectedAt()),
                    utc(createdAt));
        } catch (DuplicateKeyException ex) {
            if (String.valueOf(ex.getMessage()).contains("uk_incident_key")) {
                throw new IncidentKeyTakenException(incidentKey.value());
            }
            throw ex;
        }
        return toDomain(mapper.selectByKey(incidentKey.value()));
    }

    @Override
    public void addAffectedResources(long incidentId, Collection<Long> managedResourceIds, Instant createdAt) {
        if (!managedResourceIds.isEmpty()) {
            mapper.insertAffectedResources(incidentId, managedResourceIds, utc(createdAt));
        }
    }

    private static ApplicationException conflict(
            long incidentId, String incidentKey, IncidentStatus expectedStatus, String operation, IncidentRow current) {
        if (current == null) {
            return new ApplicationException(
                    ErrorCode.INCIDENT_NOT_FOUND,
                    "Incident not found",
                    incidentKey == null ? Map.of("incidentId", incidentId) : Map.of("incidentKey", incidentKey));
        }
        if (!current.status().equals(expectedStatus.name())) {
            return new ApplicationException(
                    ErrorCode.INCIDENT_STATE_CONFLICT,
                    "Incident status changed before " + operation,
                    Map.of(
                            "incidentKey", current.incidentKey(),
                            "currentStatus", current.status(),
                            "expectedStatuses", List.of(expectedStatus.name()),
                            "version", current.lockVersion()));
        }
        return new ApplicationException(
                ErrorCode.INCIDENT_VERSION_CONFLICT,
                "Incident version changed before " + operation,
                Map.of(
                        "incidentKey", current.incidentKey(),
                        "currentStatus", current.status(),
                        "version", current.lockVersion()));
    }

    /** DATETIME(3)：统一截断到毫秒并按 UTC 写入。 */
    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private static Incident toDomain(IncidentRow row) {
        return new Incident(
                row.id(),
                new IncidentKey(row.incidentKey()),
                row.managedSystemId(),
                row.title(),
                row.description(),
                row.impactSummary(),
                IncidentStatus.valueOf(row.status()),
                IncidentSource.valueOf(row.createdSource()),
                row.createdBy(),
                instant(row.startedAt()),
                instant(row.detectedAt()),
                instant(row.resolvedAt()),
                row.lockVersion());
    }
}
