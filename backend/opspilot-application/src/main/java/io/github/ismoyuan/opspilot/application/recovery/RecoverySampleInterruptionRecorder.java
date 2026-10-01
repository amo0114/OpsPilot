package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.dispatch.InterruptedWorkRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verification 的启动中断记录（08 TASK-083、04 §80、07 §70）：旧进程退出时仍为 RUNNING 的恢复样本调用标 FAILED/PROCESS_INTERRUPTED，
 * 只在启动路径执行（StartupRecoveryCoordinator 以本进程启动时刻为界）。该槽位保持原记录，不在同一 sampleIndex 上重试；Verification
 * 的快照、deadline 与其他样本都不改，随后由补派发的 Runner 按持久化槽位继续剩余未准入样本并按同一矩阵收束。
 */
@Service
public class RecoverySampleInterruptionRecorder implements InterruptedWorkRecorder {

    private static final Logger log = LoggerFactory.getLogger(RecoverySampleInterruptionRecorder.class);

    /** 固定文案。 */
    static final String MESSAGE = "Java process exited before this recovery sample finished";

    private final RecoverySampleInterruptionRepository samples;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RecoverySampleInterruptionRecorder(
            RecoverySampleInterruptionRepository samples, PlatformTransactionManager transactionManager, Clock clock) {
        this.samples = samples;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public int recordInterrupted(Instant startedBefore) {
        Integer marked = transaction.execute(status ->
                samples.markInterrupted(startedBefore, MESSAGE, clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        int count = marked == null ? 0 : marked;
        if (count > 0) {
            log.info("Interrupted recovery samples recorded: invocations={}", count);
        }
        return count;
    }
}
