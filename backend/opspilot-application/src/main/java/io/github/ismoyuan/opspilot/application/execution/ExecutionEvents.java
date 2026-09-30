package io.github.ismoyuan.opspilot.application.execution;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRepository.ExecutionRecord;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.timeline.ActionExecutionEventPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.ActionExecutionReconciliationPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelinePayload;
import java.time.Instant;

/** Execution 的时间线事件（01 §35、04 §82），由执行 Worker 与核对服务在各自的状态事务内写入。 */
final class ExecutionEvents {

    private final TimelineRepository timeline;

    ExecutionEvents(TimelineRepository timeline) {
        this.timeline = timeline;
    }

    /** ACTION_EXECUTION_STARTED / SUCCEEDED / FAILED。 */
    void append(
            Incident incident,
            TimelineEventType type,
            String summary,
            ExecutionRecord execution,
            String phase,
            String errorCode,
            Instant now) {
        append(
                incident,
                type,
                summary,
                new ActionExecutionEventPayloadV1(
                        incident.incidentKey().value(),
                        execution.id(),
                        execution.remediationActionId(),
                        phase,
                        errorCode),
                now);
    }

    void reconciliationAttempted(Incident incident, ExecutionRecord execution, int attemptNo, Instant now) {
        append(
                incident,
                TimelineEventType.ACTION_EXECUTION_RECONCILIATION_ATTEMPTED,
                "只读核对重启结果（第 " + attemptNo + " 次，最多 " + execution.maxReconciliationAttempts() + " 次）",
                new ActionExecutionReconciliationPayloadV1(
                        incident.incidentKey().value(),
                        execution.id(),
                        execution.remediationActionId(),
                        attemptNo,
                        execution.maxReconciliationAttempts()),
                now);
    }

    private void append(
            Incident incident, TimelineEventType type, String summary, TimelinePayload payload, Instant now) {
        timeline.append(new NewTimelineEvent(
                incident.id(), type, now, TimelineActorType.SYSTEM, null, summary, payload, Correlation.currentId()));
    }
}
