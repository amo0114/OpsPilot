package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import java.time.Instant;
import java.util.Objects;

/**
 * 恢复上下文中一个样本槽位的 OBSERVE 调用，以 RUNNING 登记（04 §18～§19：恢复调用必带 verificationId＋criterionKey＋sampleIndex，
 * 调查列与 run_no 为空）。
 *
 * @param requestPayload 快照中强类型参数的规范 JSON（CanonicalJsonWriter）
 * @param correlationId 可为空
 */
public record NewRecoverySampleInvocation(
        long incidentId,
        long recoveryVerificationId,
        String criterionKey,
        int sampleIndex,
        String capabilityKey,
        long managedResourceId,
        CapabilitySchema requestSchema,
        String requestPayload,
        Instant startedAt,
        String correlationId) {

    public NewRecoverySampleInvocation {
        Objects.requireNonNull(criterionKey, "criterionKey");
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(requestSchema, "requestSchema");
        Objects.requireNonNull(requestPayload, "requestPayload");
        Objects.requireNonNull(startedAt, "startedAt");
        if (sampleIndex < 1) {
            throw new IllegalArgumentException("sampleIndex must be >= 1");
        }
    }
}
