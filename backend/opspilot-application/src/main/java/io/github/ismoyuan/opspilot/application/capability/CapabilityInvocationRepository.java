package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * CapabilityInvocation 持久化端口（04 §17～§21）：准入时登记 RUNNING，终态只从 RUNNING 条件更新（受影响行数不为 1 则不覆盖）。
 */
public interface CapabilityInvocationRepository {

    /** @return 新 Invocation 的 id */
    long insertRunningInvestigationCall(NewInvestigationInvocation invocation);

    Optional<InvocationRecord> findByIdForUpdate(long id);

    /** RUNNING → SUCCEEDED：响应 Schema 与已结构化、已脱敏的结果（06 §119）。@return 是否由本次更新 */
    boolean markSucceeded(
            long id,
            CapabilitySchema responseSchema,
            String responsePayload,
            String rawResultRef,
            Instant finishedAt,
            long durationMs);

    /** RUNNING → FAILED：错误码与已脱敏的固定文案。@return 是否由本次更新 */
    boolean markFailed(long id, ErrorCode errorCode, String safeMessage, Instant finishedAt, long durationMs);

    /**
     * Duplicate Guard 候选（07 §57）：该调查同能力、同资源、同请求 Schema 的全部 PENDING/RUNNING，以及 finished_at 不早于
     * {@code finishedSince} 的终态调用；只返回它们的请求载荷，由调用方做规范化比较。
     */
    List<String> findGuardedRequestPayloads(
            long investigationId,
            String capabilityKey,
            long managedResourceId,
            CapabilitySchema requestSchema,
            Instant finishedSince);
}
