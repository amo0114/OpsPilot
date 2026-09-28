package io.github.ismoyuan.opspilot.application.investigation.recovery;

import java.time.Instant;

/**
 * 调查 Capability 调用的中断记录（07 §55）：只处理调查调用（均为只读 OBSERVE），不产生 Observation；
 * 恢复采样调用的中断由 Verification 恢复处理（TASK-083）。
 */
public interface InvocationInterruptionRepository {

    /**
     * startedBefore 之前开始、仍为 RUNNING 的调查调用 → FAILED/PROCESS_INTERRUPTED，耗时记到 finishedAt。
     *
     * @return 标记条数
     */
    int markInterrupted(Instant startedBefore, String safeMessage, Instant finishedAt);
}
