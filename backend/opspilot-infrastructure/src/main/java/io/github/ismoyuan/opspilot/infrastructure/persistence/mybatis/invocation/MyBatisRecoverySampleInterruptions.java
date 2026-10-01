package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.invocation;

import io.github.ismoyuan.opspilot.application.recovery.RecoverySampleInterruptionRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Repository;

/** {@link RecoverySampleInterruptionRepository} 的 MyBatis 实现，与调查调用的中断记录共用映射器。 */
@Repository
class MyBatisRecoverySampleInterruptions implements RecoverySampleInterruptionRepository {

    private final InvocationInterruptionMapper mapper;

    MyBatisRecoverySampleInterruptions(InvocationInterruptionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int markInterrupted(Instant startedBefore, String safeMessage, Instant finishedAt) {
        List<Long> running = mapper.selectRunningRecoverySamplesStartedBefore(utc(startedBefore));
        if (running.isEmpty()) {
            return 0;
        }
        return mapper.markInterruptedRecoverySamples(
                running, ErrorCode.PROCESS_INTERRUPTED.name(), safeMessage, utc(finishedAt));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
