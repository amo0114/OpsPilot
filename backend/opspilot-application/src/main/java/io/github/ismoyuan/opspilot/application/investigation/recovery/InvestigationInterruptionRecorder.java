package io.github.ismoyuan.opspilot.application.investigation.recovery;

import io.github.ismoyuan.opspilot.application.dispatch.InterruptedWorkRecorder;
import io.github.ismoyuan.opspilot.application.investigation.step.AgentStepRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 调查的中断记录（08 TASK-043、07 §52、§54～§55、02 §28）：旧进程遗留的 RUNNING AgentStep 与调查调用标 FAILED/PROCESS_INTERRUPTED。
 * 不改 Investigation：run、Stop、截止时间、本轮额度与连续 AI 失败计数都保持原样——Java 中断不是模型失败，也不退还已准入额度；
 * 不伪造 Observation。之后由派发的原 run Worker 经准入决定继续或收束。
 */
@Service
public class InvestigationInterruptionRecorder implements InterruptedWorkRecorder {

    private static final Logger log = LoggerFactory.getLogger(InvestigationInterruptionRecorder.class);

    /** 固定文案，不含请求或模型输出。 */
    static final String MESSAGE = "Java process exited before this call finished";

    private final AgentStepRepository steps;
    private final InvocationInterruptionRepository invocations;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public InvestigationInterruptionRecorder(
            AgentStepRepository steps,
            InvocationInterruptionRepository invocations,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.steps = steps;
        this.invocations = invocations;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public int recordInterrupted(Instant startedBefore) {
        int[] marked = transaction.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            return new int[] {
                steps.markInterrupted(startedBefore, MESSAGE, now),
                invocations.markInterrupted(startedBefore, MESSAGE, now)
            };
        });
        if (marked[0] + marked[1] > 0) {
            log.info("Interrupted investigation work recorded: agentSteps={} invocations={}", marked[0], marked[1]);
        }
        return marked[0] + marked[1];
    }
}
