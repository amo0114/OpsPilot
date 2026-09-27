package io.github.ismoyuan.opspilot.application.investigation.query;

import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import java.time.Instant;

/**
 * 05 §53：结构化载荷、来源窗口与来源调用。载荷写入前已脱敏（TASK-049～051 负责生成），查询不再改写。
 *
 * @param payload schemaName/schemaVersion 对应的 JSON 对象文本
 * @param windowStart 可为空，与 windowEnd 成对
 */
public record ObservationDetailView(
        long id,
        ObservationKind kind,
        ResourceRefView resource,
        String summary,
        Instant observedAt,
        String schemaName,
        int schemaVersion,
        String payload,
        Instant windowStart,
        Instant windowEnd,
        long capabilityInvocationId,
        String capabilityKey,
        int runNo) {}
