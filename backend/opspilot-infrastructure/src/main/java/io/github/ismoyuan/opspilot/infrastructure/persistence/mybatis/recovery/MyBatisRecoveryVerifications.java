package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.recovery;

import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationRepository;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link RecoveryVerificationRepository} 的 MyBatis 实现；时间以 UTC 毫秒精度存储（04 §3）。 */
@Repository
class MyBatisRecoveryVerifications implements RecoveryVerificationRepository {

    private final RecoveryVerificationMapper mapper;

    MyBatisRecoveryVerifications(RecoveryVerificationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<RecoveryVerificationRecord> findById(long verificationId) {
        return Optional.ofNullable(mapper.selectById(verificationId))
                .map(row -> new RecoveryVerificationRecord(
                        row.id(),
                        row.incidentId(),
                        row.verificationNo(),
                        RecoveryVerificationStatus.valueOf(row.status()),
                        row.policySnapshot(),
                        row.deadlineAt().toInstant(ZoneOffset.UTC),
                        row.startedAt() == null ? null : row.startedAt().toInstant(ZoneOffset.UTC),
                        row.lockVersion()));
    }

    @Override
    public boolean markRunning(long verificationId, long expectedVersion, Instant startedAt) {
        return mapper.markRunning(verificationId, expectedVersion, utc(startedAt)) == 1;
    }

    @Override
    public boolean markFinished(
            long verificationId,
            RecoveryVerificationStatus from,
            long expectedVersion,
            RecoveryVerificationStatus outcome,
            String resultSummary,
            String resultPayload,
            Instant finishedAt) {
        if (from.terminal() || !outcome.terminal()) {
            throw new IllegalArgumentException("only an active verification can reach a terminal outcome");
        }
        return mapper.markFinished(
                        verificationId,
                        from.name(),
                        expectedVersion,
                        outcome.name(),
                        resultSummary,
                        resultPayload,
                        utc(finishedAt))
                == 1;
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
