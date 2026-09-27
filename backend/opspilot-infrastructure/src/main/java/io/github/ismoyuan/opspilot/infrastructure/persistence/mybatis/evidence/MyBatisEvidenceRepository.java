package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.evidence;

import io.github.ismoyuan.opspilot.application.evidence.EvidenceRepository;
import io.github.ismoyuan.opspilot.domain.evidence.Evidence;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.evidence.NewEvidence;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisEvidenceRepository implements EvidenceRepository {

    private final EvidenceMapper mapper;

    MyBatisEvidenceRepository(EvidenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Evidence insert(NewEvidence evidence, Instant createdAt) {
        EvidenceInsert insert = new EvidenceInsert(
                evidence.investigationId(),
                evidence.observationId(),
                evidence.hypothesisId(),
                evidence.relation().name(),
                evidence.reason(),
                LocalDateTime.ofInstant(createdAt.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
        mapper.insert(insert);
        return toDomain(mapper.selectById(insert.getId()));
    }

    @Override
    public Optional<Evidence> findByObservationAndHypothesis(long observationId, long hypothesisId) {
        return Optional.ofNullable(mapper.selectByObservationAndHypothesisForShare(observationId, hypothesisId))
                .map(MyBatisEvidenceRepository::toDomain);
    }

    @Override
    public List<Evidence> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectByIds(ids).stream()
                .map(MyBatisEvidenceRepository::toDomain)
                .toList();
    }

    private static Evidence toDomain(EvidenceRow row) {
        return new Evidence(
                row.id(),
                new NewEvidence(
                        row.investigationId(),
                        row.observationId(),
                        row.hypothesisId(),
                        EvidenceRelation.valueOf(row.relation()),
                        row.reason()),
                row.createdAt().toInstant(ZoneOffset.UTC));
    }
}
