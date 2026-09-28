package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.invocation;

import io.github.ismoyuan.opspilot.application.investigation.recovery.InvocationInterruptionRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisInvocationInterruptionRepository implements InvocationInterruptionRepository {

    private final InvocationInterruptionMapper mapper;

    MyBatisInvocationInterruptionRepository(InvocationInterruptionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int markInterrupted(Instant startedBefore, String safeMessage, Instant finishedAt) {
        List<Long> running = mapper.selectRunningInvestigationCallsStartedBefore(utc(startedBefore));
        if (running.isEmpty()) {
            return 0;
        }
        return mapper.markInterrupted(running, ErrorCode.PROCESS_INTERRUPTED.name(), safeMessage, utc(finishedAt));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
