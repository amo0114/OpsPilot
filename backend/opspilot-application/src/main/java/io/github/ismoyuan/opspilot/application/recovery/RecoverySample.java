package io.github.ismoyuan.opspilot.application.recovery;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个恢复样本槽位的持久化事实（04 §18～§19、§80）：身份来自 verificationId＋criterionKey＋sampleIndex 的唯一 Invocation，
 * 不从 Observation 条数推断。
 *
 * @param sampledAt 真实采样时间（Observation 的 observed_at；没有 Observation 时为调用完成时间），失败或未完成为空
 * @param value 成功样本的投影值；失败或未完成为空
 */
public record RecoverySample(
        int sampleIndex, long invocationId, Status status, Instant sampledAt, ProjectedValue value) {

    public enum Status {
        SUCCEEDED,
        FAILED,
        /** 仍在进行或进程中断遗留；不能作为数据。 */
        RUNNING
    }

    public RecoverySample {
        if (sampleIndex < 1) {
            throw new IllegalArgumentException("sampleIndex must be >= 1");
        }
        Objects.requireNonNull(status, "status");
        if (status == Status.SUCCEEDED && (sampledAt == null || value == null)) {
            throw new IllegalArgumentException("a succeeded sample needs its time and value");
        }
    }
}
