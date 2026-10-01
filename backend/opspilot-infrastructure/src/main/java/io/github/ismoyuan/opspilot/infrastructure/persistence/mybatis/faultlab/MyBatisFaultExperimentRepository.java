package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.FaultExperimentRepository;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1;
import io.github.ismoyuan.opspilot.domain.faultlab.FaultExperimentStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisFaultExperimentRepository implements FaultExperimentRepository {

    private final FaultExperimentMapper mapper;

    MyBatisFaultExperimentRepository(FaultExperimentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void lockSystem(long managedSystemId) {
        if (mapper.lockSystem(managedSystemId) == null) {
            throw new IllegalStateException("managed system disappeared: " + managedSystemId);
        }
    }

    @Override
    public boolean existsInProgress(long managedSystemId, Long exceptId) {
        return mapper.countInProgress(managedSystemId, exceptId) > 0;
    }

    @Override
    public long insertInjecting(
            String scenarioKey,
            long managedSystemId,
            long targetResourceId,
            String groundTruthPayload,
            Instant createdAt) {
        FaultExperimentMapper.GeneratedKey key = new FaultExperimentMapper.GeneratedKey();
        mapper.insertInjecting(
                key,
                new FaultExperimentMapper.ExperimentInsert(
                        scenarioKey,
                        managedSystemId,
                        targetResourceId,
                        FaultGroundTruthV1.SCHEMA_NAME,
                        FaultGroundTruthV1.SCHEMA_VERSION,
                        groundTruthPayload,
                        utc(createdAt)));
        return key.getId();
    }

    @Override
    public Optional<Long> findSystemId(long experimentId) {
        return Optional.ofNullable(mapper.selectSystemId(experimentId));
    }

    @Override
    public Optional<FaultExperimentRecord> findForUpdate(long experimentId) {
        return Optional.ofNullable(mapper.selectForUpdate(experimentId))
                .map(row -> new FaultExperimentRecord(
                        row.id(),
                        row.scenarioKey(),
                        row.managedSystemId(),
                        row.systemKey(),
                        row.systemEnvironment(),
                        row.targetResourceId(),
                        row.targetResourceKey(),
                        row.incidentId(),
                        FaultExperimentStatus.valueOf(row.status()),
                        instant(row.injectedAt()),
                        instant(row.resetAt())));
    }

    @Override
    public boolean markActive(long experimentId, long incidentId, Instant injectedAt, Instant now) {
        return mapper.markActive(experimentId, incidentId, utc(injectedAt), utc(now)) == 1;
    }

    @Override
    public boolean markFailed(long experimentId, FaultExperimentStatus from, String errorMessage, Instant now) {
        return mapper.markFailed(experimentId, from.name(), errorMessage, utc(now)) == 1;
    }

    @Override
    public boolean markResetting(long experimentId, FaultExperimentStatus from, Instant now) {
        return mapper.markResetting(experimentId, from.name(), utc(now)) == 1;
    }

    @Override
    public boolean markReset(long experimentId, Instant resetAt) {
        return mapper.markReset(experimentId, utc(resetAt)) == 1;
    }

    @Override
    public int markInterrupted(Instant startedBefore, String errorMessage, Instant now) {
        return mapper.markInterrupted(utc(startedBefore), errorMessage, utc(now));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
