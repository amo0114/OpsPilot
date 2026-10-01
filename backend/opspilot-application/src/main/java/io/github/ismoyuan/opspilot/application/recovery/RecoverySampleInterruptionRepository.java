package io.github.ismoyuan.opspilot.application.recovery;

import java.time.Instant;

/** 恢复样本调用的中断记录端口（04 §80、07 §70）：只处理 recovery_verification_id 非空的调用。 */
public interface RecoverySampleInterruptionRepository {

    /**
     * startedBefore 之前开始、仍为 RUNNING 的恢复样本调用 → FAILED/PROCESS_INTERRUPTED，耗时记到 finishedAt；不产生 Observation，
     * 不改样本身份或开始时间。
     *
     * @return 标记条数
     */
    int markInterrupted(Instant startedBefore, String safeMessage, Instant finishedAt);
}
