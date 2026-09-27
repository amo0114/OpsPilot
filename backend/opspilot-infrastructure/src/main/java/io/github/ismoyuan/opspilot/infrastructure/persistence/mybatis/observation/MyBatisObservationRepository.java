package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.observation;

import io.github.ismoyuan.opspilot.application.observation.ObservationRepository;
import io.github.ismoyuan.opspilot.domain.observation.NewObservation;
import io.github.ismoyuan.opspilot.domain.observation.Observation;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisObservationRepository implements ObservationRepository {

    private final ObservationMapper mapper;

    MyBatisObservationRepository(ObservationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Observation insert(NewObservation observation, Instant createdAt) {
        ObservationInsert insert = new ObservationInsert(
                observation.incidentId(),
                observation.investigationId(),
                observation.recoveryVerificationId(),
                observation.capabilityInvocationId(),
                observation.managedResourceId(),
                observation.kind().name(),
                observation.schemaName(),
                observation.schemaVersion(),
                observation.payload(),
                observation.summary(),
                utc(observation.observedAt()),
                utc(observation.windowStart()),
                utc(observation.windowEnd()),
                utc(createdAt));
        if (mapper.insertFromSucceededInvocation(insert) != 1) {
            throw new IllegalArgumentException("Observation does not match a succeeded source invocation: invocationId="
                    + observation.capabilityInvocationId());
        }
        return toDomain(mapper.selectById(insert.getId()));
    }

    @Override
    public Optional<Observation> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisObservationRepository::toDomain);
    }

    @Override
    public List<Observation> findByInvestigationId(long investigationId) {
        return mapper.selectByInvestigationId(investigationId).stream()
                .map(MyBatisObservationRepository::toDomain)
                .toList();
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private static Observation toDomain(ObservationRow row) {
        return new Observation(
                row.id(),
                new NewObservation(
                        row.incidentId(),
                        row.investigationId(),
                        row.recoveryVerificationId(),
                        row.capabilityInvocationId(),
                        row.managedResourceId(),
                        ObservationKind.valueOf(row.observationKind()),
                        row.schemaName(),
                        row.schemaVersion(),
                        row.payload(),
                        row.summary(),
                        instant(row.observedAt()),
                        instant(row.windowStart()),
                        instant(row.windowEnd())),
                instant(row.createdAt()));
    }
}
