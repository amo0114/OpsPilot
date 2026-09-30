package io.github.ismoyuan.opspilot.application.capability;

import java.time.Instant;

/**
 * 一个恢复样本槽位的持久化调用（04 §18、§80）：身份、状态、真实时间与成功时的结构化响应。
 *
 * @param finishedAt 未结束为空
 * @param observedAt 该调用 Observation 的最早 observed_at；没有 Observation 时为空
 * @param responsePayload 成功时的结构化结果，其余为空
 */
public record RecoverySampleInvocation(
        long id,
        String criterionKey,
        int sampleIndex,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Instant observedAt,
        String responseSchemaName,
        Integer responseSchemaVersion,
        String responsePayload) {}
