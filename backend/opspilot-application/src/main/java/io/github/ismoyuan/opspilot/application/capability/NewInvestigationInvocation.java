package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import java.time.Instant;
import java.util.Objects;

/**
 * 调查上下文中通过准入的一次 OBSERVE 调用，以 RUNNING 登记（04 §18～§19：调查调用必带 run_no，恢复列为空）。
 *
 * @param requestPayload 参数的规范 JSON（CanonicalJsonWriter）
 * @param correlationId 可为空
 */
public record NewInvestigationInvocation(
        long incidentId,
        long investigationId,
        int runNo,
        String capabilityKey,
        long managedResourceId,
        CapabilitySchema requestSchema,
        String requestPayload,
        Instant startedAt,
        String correlationId) {

    public NewInvestigationInvocation {
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(requestSchema, "requestSchema");
        Objects.requireNonNull(requestPayload, "requestPayload");
        Objects.requireNonNull(startedAt, "startedAt");
        if (runNo < 1) {
            throw new IllegalArgumentException("runNo must be >= 1");
        }
    }
}
