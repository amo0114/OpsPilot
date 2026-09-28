package io.github.ismoyuan.opspilot.application.capability;

import java.time.Instant;

/**
 * 结果落账所需的 Invocation 身份与状态（04 §18）；调查调用与恢复调用二选一，结果事务按 Invocation 自身上下文写入。
 *
 * @param investigationId 恢复调用为空
 * @param recoveryVerificationId 调查调用为空
 * @param runNo 恢复调用为空
 */
public record InvocationRecord(
        long id,
        long incidentId,
        Long investigationId,
        Long recoveryVerificationId,
        Integer runNo,
        String capabilityKey,
        long managedResourceId,
        String status,
        Instant startedAt) {

    public boolean isRunning() {
        return "RUNNING".equals(status);
    }
}
