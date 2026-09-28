package io.github.ismoyuan.opspilot.application.investigation.step;

import java.time.Instant;

/**
 * 结果事务内、持锁确认仍属当前 run 的 Step 身份，交给 Intent 处置使用。
 *
 * @param runStartedAt 本轮起点，用于判定哪些 Evidence 属于本轮
 */
public record ActiveStep(long stepId, long incidentId, long investigationId, int runNo, Instant runStartedAt) {}
