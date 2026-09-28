package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import io.github.ismoyuan.opspilot.application.investigation.orchestration.ReferenceScopeQuery;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisReferenceScopeQuery implements ReferenceScopeQuery {

    private final ReferenceScopeMapper mapper;

    MyBatisReferenceScopeQuery(ReferenceScopeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean isObservationInScope(long investigationId, int runNo, long observationId) {
        return mapper.countObservationInScope(investigationId, runNo, observationId) > 0;
    }

    @Override
    public Set<Long> evidenceOutOfScope(long investigationId, Instant runStartedAt, Collection<Long> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> outside = new HashSet<>(evidenceIds);
        outside.removeAll(mapper.selectEvidenceInScope(
                investigationId,
                LocalDateTime.ofInstant(runStartedAt.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC),
                evidenceIds));
        return Set.copyOf(outside);
    }
}
