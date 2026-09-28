package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import io.github.ismoyuan.opspilot.application.investigation.InvestigationRepository;
import io.github.ismoyuan.opspilot.domain.investigation.Investigation;
import io.github.ismoyuan.opspilot.domain.investigation.InvestigationLimits;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisInvestigationRepository implements InvestigationRepository {

    private final InvestigationMapper mapper;

    MyBatisInvestigationRepository(InvestigationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean existsForIncident(long incidentId) {
        return mapper.countByIncidentId(incidentId) > 0;
    }

    @Override
    public Optional<Investigation> findByIncidentIdForUpdate(long incidentId) {
        return Optional.ofNullable(mapper.selectByIncidentIdForUpdate(incidentId))
                .map(MyBatisInvestigationRepository::toDomain);
    }

    @Override
    public Investigation insertFirstRun(long incidentId, InvestigationLimits limits, Instant now) {
        mapper.insertFirstRun(
                incidentId,
                utc(now),
                limits.maxCapabilityCalls(),
                limits.maxDurationSeconds(),
                limits.agentStepTimeoutSeconds(),
                limits.maxConsecutiveAiFailures());
        return toDomain(mapper.selectByIncidentIdForUpdate(incidentId));
    }

    @Override
    public Investigation saveNextRun(Investigation previous, Investigation next) {
        int updated = mapper.startNextRun(
                previous.id(),
                previous.currentRunNo(),
                previous.version(),
                next.currentRunNo(),
                utc(next.currentRunStartedAt()));
        if (updated != 1) {
            // 调用方已在同一事务持有行锁，未命中说明违反了锁序约定
            throw new IllegalStateException("Investigation changed while locked: id=" + previous.id());
        }
        return toDomain(mapper.selectByIncidentIdForUpdate(previous.incidentId()));
    }

    @Override
    public Investigation saveStopRequest(Investigation previous, Investigation stopped) {
        int updated = mapper.requestStop(
                previous.id(),
                previous.currentRunNo(),
                previous.version(),
                utc(stopped.stopRequestedAt()),
                stopped.stopRequestedBy());
        if (updated != 1) {
            throw new IllegalStateException("Investigation changed while locked: id=" + previous.id());
        }
        return toDomain(mapper.selectByIncidentIdForUpdate(previous.incidentId()));
    }

    @Override
    public Investigation saveAiFailureCount(Investigation previous, Investigation updated, Instant at) {
        int changed = mapper.updateAiFailureCount(
                previous.id(),
                previous.currentRunNo(),
                previous.version(),
                updated.consecutiveAiFailureCount(),
                utc(at));
        if (changed != 1) {
            throw new IllegalStateException("Investigation changed while locked: id=" + previous.id());
        }
        return toDomain(mapper.selectByIncidentIdForUpdate(previous.incidentId()));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private static Investigation toDomain(InvestigationRow row) {
        return new Investigation(
                row.id(),
                row.incidentId(),
                instant(row.startedAt()),
                instant(row.lastActivityAt()),
                row.currentRunNo(),
                instant(row.currentRunStartedAt()),
                row.currentRunCapabilityCount(),
                row.capabilityCallCount(),
                row.consecutiveAiFailureCount(),
                instant(row.stopRequestedAt()),
                row.stopRequestedBy(),
                new InvestigationLimits(
                        row.maxCapabilityCalls(),
                        row.maxDurationSeconds(),
                        row.agentStepTimeoutSeconds(),
                        row.maxConsecutiveAiFailures()),
                row.lockVersion());
    }
}
