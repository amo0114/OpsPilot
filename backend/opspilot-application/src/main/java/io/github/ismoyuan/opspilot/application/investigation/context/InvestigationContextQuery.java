package io.github.ismoyuan.opspilot.application.investigation.context;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 上下文只读查询（08 TASK-037）。只读业务事实表的选定列：不读 Fault Lab 表、Ground Truth、凭证、Invocation 请求/响应、
 * Observation 或 Timeline 的原始 payload（09 §20～§22、02 §15）。
 */
public interface InvestigationContextQuery {

    /** Incident 及其 Investigation；尚未开始调查时为空。 */
    Optional<ContextHead> findHead(long incidentId);

    List<InvestigationStepRequest.AffectedResource> findAffectedResources(long incidentId);

    /** 整个 Investigation 的 Hypothesis（03 §20），按 id 升序。 */
    List<InvestigationStepRequest.Hypothesis> findHypotheses(long investigationId);

    /**
     * 本轮建立的 Evidence 与任一 Diagnosis 冻结引用的历史 Evidence，按 id 升序（08 TASK-037）。
     *
     * @param runStartedAt 本轮起点；此后创建的 Evidence 属于本轮
     */
    List<InvestigationStepRequest.Evidence> findContextEvidence(long investigationId, Instant runStartedAt);

    /**
     * 来源调用属于本轮的调查 Observation，加上 {@code referencedObservationIds} 中的历史 Observation（被上下文 Evidence 引用），
     * 按 id 升序。旧 run 未被引用的迟到结果不在其中。同一调用产生的 LOG_PATTERN 只取 id 最小的 {@code maxLogPatternsPerInvocation} 条
     * （提取顺序即出现次数降序，06 §122），被引用的除外。
     */
    List<InvestigationStepRequest.Observation> findContextObservations(
            long investigationId,
            int runNo,
            Collection<Long> referencedObservationIds,
            int maxLogPatternsPerInvocation);

    Optional<InvestigationStepRequest.CurrentDiagnosis> findLatestDiagnosis(long investigationId);

    /** 最近 {@code limit} 条允许类型的时间线（只取类型、时间与人类可读摘要），按时间先后。 */
    List<InvestigationStepRequest.TimelineEntry> findRecentTimeline(long incidentId, Set<String> eventTypes, int limit);
}
