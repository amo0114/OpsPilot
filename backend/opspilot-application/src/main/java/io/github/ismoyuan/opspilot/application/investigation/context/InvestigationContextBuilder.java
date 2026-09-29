package io.github.ismoyuan.opspilot.application.investigation.context;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.capability.logs.LogsSettings;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 构造 InvestigationStepRequest 的上下文（08 TASK-037、02 §15、05 §77）。在只读事务中读取一致快照；事务之外的准入 Guard 仍做最后校验。
 *
 * <p>选择规则：Hypothesis 取整个 Investigation；Evidence 取本轮建立的与以前 Diagnosis 冻结引用的；Observation 取来源调用属于本轮的，
 * 再加这些 Evidence 引用的历史 Observation（以 runNo 标出历史身份）；旧 run 未被诊断引用的迟到结果不进入。每次 logs.search 至多
 * {@link LogsSettings#aiContextPatterns()} 个模式进入（06 §53、§122，按提取顺序即出现次数），被 Evidence 引用的总是进入。时间线只取调查相关类型，
 * Fault Lab 与控制类事件即使将来加入也不会进入（09 §21～§22）。不读取 Ground Truth、凭证或任何原始 payload。
 */
@Service
public class InvestigationContextBuilder {

    /** 进入 AI 上下文的时间线条数（本批取值）。 */
    public static final int RECENT_TIMELINE_LIMIT = 20;

    /**
     * 可进入 AI 上下文的时间线事件类型；新类型默认不进入。能力调用失败与请求被拒是给当前 run 的反馈（06 §124，B18）：摘要只有能力、资源与
     * 错误/原因码；调用登记与 Observation 记录不进入（预算与 Observation 已在上下文中）。
     */
    public static final Set<TimelineEventType> CONTEXT_EVENT_TYPES = EnumSet.of(
            TimelineEventType.INCIDENT_CREATED,
            TimelineEventType.INVESTIGATION_STARTED,
            TimelineEventType.INVESTIGATION_STOP_REQUESTED,
            TimelineEventType.HYPOTHESIS_CREATED,
            TimelineEventType.HYPOTHESIS_STATUS_CHANGED,
            TimelineEventType.EVIDENCE_LINKED,
            TimelineEventType.DIAGNOSIS_CREATED,
            TimelineEventType.CAPABILITY_FAILED,
            TimelineEventType.CAPABILITY_REQUEST_REJECTED);

    private final InvestigationContextQuery query;
    private final CapabilityDescriptorSource capabilities;
    private final LogsSettings logs;
    private final Clock clock;

    public InvestigationContextBuilder(
            InvestigationContextQuery query, CapabilityDescriptorSource capabilities, LogsSettings logs, Clock clock) {
        this.query = query;
        this.capabilities = capabilities;
        this.logs = logs;
        this.clock = clock;
    }

    /** @return 不在调查中或 run 已不是 {@code runNo} 时为空（调用方退出，不为旧 run 准备工作） */
    @Transactional(readOnly = true)
    public Optional<InvestigationStepContext> build(long incidentId, int runNo) {
        Optional<ContextHead> found = query.findHead(incidentId);
        if (found.isEmpty()
                || found.get().status() != IncidentStatus.INVESTIGATING
                || found.get().currentRunNo() != runNo) {
            return Optional.empty();
        }
        ContextHead head = found.get();
        List<InvestigationStepRequest.Evidence> evidence =
                query.findContextEvidence(head.investigationId(), head.currentRunStartedAt());
        Set<Long> referenced = evidence.stream()
                .map(InvestigationStepRequest.Evidence::observationId)
                .collect(Collectors.toSet());
        return Optional.of(new InvestigationStepContext(
                head.incidentId(),
                head.investigationId(),
                head.currentRunNo(),
                new InvestigationStepRequest.Incident(
                        head.incidentKey(), head.title(), head.impactSummary(), head.startedAt()),
                query.findAffectedResources(incidentId),
                query.findHypotheses(head.investigationId()),
                query.findContextObservations(
                        head.investigationId(), head.currentRunNo(), referenced, logs.aiContextPatterns()),
                evidence,
                query.findLatestDiagnosis(head.investigationId()).orElse(null),
                capabilities.describe(incidentId),
                budget(head, clock.instant()),
                query.findRecentTimeline(
                        incidentId,
                        CONTEXT_EVENT_TYPES.stream().map(Enum::name).collect(Collectors.toSet()),
                        RECENT_TIMELINE_LIMIT)));
    }

    /** 本轮额度（05 §50 口径）：已用秒数截在 [0, 上限]。 */
    private static InvestigationStepRequest.Budget budget(ContextHead head, Instant now) {
        long elapsed =
                Math.clamp(Duration.between(head.currentRunStartedAt(), now).toSeconds(), 0, head.maxDurationSeconds());
        return new InvestigationStepRequest.Budget(
                InvestigationStepRequest.BudgetScope.ACTIVE_RUN,
                head.currentRunCapabilityCount(),
                head.maxCapabilityCalls(),
                Math.max(head.maxCapabilityCalls() - head.currentRunCapabilityCount(), 0),
                (int) elapsed,
                head.maxDurationSeconds());
    }
}
