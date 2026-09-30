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

    /**
     * 登记一个恢复样本槽位的 RUNNING 调用（04 §18～§19、06 §113）。
     *
     * @return 新 Invocation 的 id；该 verificationId＋criterionKey＋sampleIndex 已被占用时为空（不在同一槽位重试）
     */
    Optional<Long> insertRunningRecoverySample(NewRecoverySampleInvocation invocation);

    /** 不加锁读取一次 Verification 的全部恢复样本调用（按 criterionKey、sampleIndex 升序）。 */
    List<RecoverySampleInvocation> findRecoverySamples(long recoveryVerificationId);

    /** 不加锁读取调用所属 Incident（创建后不变），供结果事务先锁 Incident。 */
    Optional<Long> findIncidentId(long id);

    Optional<InvocationRecord> findByIdForUpdate(long id);

    /** 不加锁读取该 Incident 下仍为 RUNNING 的调查调用 id（按 id 升序；不含恢复采样调用）。 */
    List<Long> findRunningInvestigationCallIds(long incidentId);

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
