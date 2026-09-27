package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.hypothesis;

import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisRepository;
import io.github.ismoyuan.opspilot.domain.hypothesis.Hypothesis;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import io.github.ismoyuan.opspilot.domain.hypothesis.NewHypothesis;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisHypothesisRepository implements HypothesisRepository {

    private final HypothesisMapper mapper;

    MyBatisHypothesisRepository(HypothesisMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Hypothesis insert(NewHypothesis hypothesis, Instant createdAt) {
        HypothesisInsert insert = new HypothesisInsert(
                hypothesis.investigationId(), hypothesis.title(), hypothesis.description(), utc(createdAt));
        mapper.insertPending(insert);
        return toDomain(mapper.selectById(insert.getId()));
    }

    @Override
    public Optional<Hypothesis> findByIdForUpdate(long id) {
        return Optional.ofNullable(mapper.selectByIdForUpdate(id)).map(MyBatisHypothesisRepository::toDomain);
    }

    @Override
    public Hypothesis saveStatus(Hypothesis previous, Hypothesis changed) {
        int updated = mapper.changeStatus(
                previous.id(),
                previous.investigationId(),
                previous.status().name(),
                previous.version(),
                changed.status().name(),
                utc(changed.updatedAt()));
        if (updated != 1) {
            // 调用方已在同一事务持有行锁，未命中说明违反了锁序约定
            throw new IllegalStateException("Hypothesis changed while locked: id=" + previous.id());
        }
        return toDomain(mapper.selectById(previous.id()));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private static Hypothesis toDomain(HypothesisRow row) {
        return new Hypothesis(
                row.id(),
                row.investigationId(),
                row.title(),
                row.description(),
                HypothesisStatus.valueOf(row.status()),
                row.createdAt().toInstant(ZoneOffset.UTC),
                row.updatedAt().toInstant(ZoneOffset.UTC),
                row.lockVersion());
    }
}
