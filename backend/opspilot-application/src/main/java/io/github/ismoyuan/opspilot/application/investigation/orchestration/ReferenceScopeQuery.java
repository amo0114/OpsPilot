package io.github.ismoyuan.opspilot.application.investigation.orchestration;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * AI 在当前 run 中可以引用的调查事实（08 TASK-037/040、07 §42）：本轮观测与证据，以及以前 Diagnosis 冻结引用的历史证据及其观测。
 * 旧 run 未被诊断引用的迟到结果不能借新 Evidence 或 Diagnosis 进入本轮（B10-R1）。只读。
 */
public interface ReferenceScopeQuery {

    /** 调查观测且来源调用属于 {@code runNo}，或被任一 Diagnosis 冻结的 Evidence 引用。 */
    boolean isObservationInScope(long investigationId, int runNo, long observationId);

    /**
     * @param runStartedAt 本轮起点；此后建立的 Evidence 属于本轮
     * @return 给定 id 中不在范围内的（不属于该调查、既非本轮建立也未被冻结，或不存在）
     */
    Set<Long> evidenceOutOfScope(long investigationId, Instant runStartedAt, Collection<Long> evidenceIds);
}
