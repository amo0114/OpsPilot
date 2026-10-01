package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.recovery;

import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationQuery;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisRecoveryVerificationQuery implements RecoveryVerificationQuery {

    private final RecoveryVerificationMapper mapper;

    MyBatisRecoveryVerificationQuery(RecoveryVerificationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<VerificationSnapshot> findLatest(long incidentId) {
        return Optional.ofNullable(mapper.selectLatest(incidentId))
                .map(row -> new VerificationSnapshot(
                        row.id(),
                        row.verificationNo(),
                        RecoveryVerificationStatus.valueOf(row.status()),
                        row.actionExecutionId(),
                        row.resourceKey(),
                        row.resourceName(),
                        row.policySnapshot(),
                        row.resultSummary(),
                        row.resultPayload(),
                        instant(row.deadlineAt()),
                        instant(row.startedAt()),
                        instant(row.finishedAt())));
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
