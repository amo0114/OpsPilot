package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.observation.ObservationRepository;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.observation.NewObservation;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.timeline.CapabilityFailedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.ObservationRecordedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invocation 结果短事务（08 TASK-048、04 §18、06 §33）：锁定该 Invocation，只从 RUNNING 条件更新；成功才写入响应并以其上下文产生
 * Observation（调用失败不产生 Observation，CAP-INV-006），失败只记错误。不再改预算，也不看当前 run——旧 run 在途调用的真实结果照常写回
 * 原 Invocation，Observation 归属原调用，不改变新 run 的控制与计数（01 §11）。已不是 RUNNING（如已被中断标记）时不覆盖。
 *
 * <p>04 §72 事务二：锁序 Incident → Invocation；成功时每条 Observation 追加一条 OBSERVATION_RECORDED，失败追加 CAPABILITY_FAILED
 * （载荷只含错误码），已是终态时什么也不写（B18/TASK-058 补齐）。
 *
 * <p>失败文案是 error_message 的唯一写入口，落账前再经 {@link Sanitizer}（06 §33 保存脱敏错误），Provider 带出的片段也不会留下凭据。
 * 成功结果在交给本类之前已由结果管线脱敏（TASK-049～051）。
 */
@Service
public class CapabilityResultRecorder {

    /** 与 capability_invocation.error_message 列长度一致。 */
    static final int MESSAGE_MAX = 1000;

    private final CapabilityInvocationRepository invocations;
    private final ObservationRepository observations;
    private final Sanitizer sanitizer;
    private final IncidentRepository incidents;
    private final ManagedResourceRepository resources;
    private final TimelineRepository timeline;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CapabilityResultRecorder(
            CapabilityInvocationRepository invocations,
            ObservationRepository observations,
            Sanitizer sanitizer,
            IncidentRepository incidents,
            ManagedResourceRepository resources,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.invocations = invocations;
        this.observations = observations;
        this.sanitizer = sanitizer;
        this.incidents = incidents;
        this.resources = resources;
        this.timeline = timeline;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** @return 产生的 Observation id；Invocation 已不是 RUNNING 时为空且不写任何数据 */
    public Optional<List<Long>> recordSucceeded(long invocationId, InvocationOutcome.Succeeded outcome) {
        return transaction.execute(status -> {
            Optional<Locked> running = lockRunning(invocationId);
            if (running.isEmpty()) {
                return Optional.<List<Long>>empty();
            }
            InvocationRecord invocation = running.get().invocation();
            Instant now = now();
            invocations.markSucceeded(
                    invocationId,
                    outcome.resultSchema(),
                    outcome.resultPayload(),
                    outcome.rawResultRef(),
                    now,
                    durationMillis(invocation, now));
            List<Long> created = new ArrayList<>();
            for (ObservationDraft draft : outcome.observations()) {
                long observationId = observations
                        .insert(
                                new NewObservation(
                                        invocation.incidentId(),
                                        invocation.investigationId(),
                                        invocation.recoveryVerificationId(),
                                        invocation.id(),
                                        invocation.managedResourceId(),
                                        draft.kind(),
                                        draft.schemaName(),
                                        draft.schemaVersion(),
                                        draft.payload(),
                                        draft.summary(),
                                        draft.observedAt(),
                                        draft.windowStart(),
                                        draft.windowEnd()),
                                now)
                        .id();
                created.add(observationId);
                timeline.append(new NewTimelineEvent(
                        invocation.incidentId(),
                        TimelineEventType.OBSERVATION_RECORDED,
                        now,
                        TimelineActorType.SYSTEM,
                        null,
                        draft.summary(),
                        new ObservationRecordedPayloadV1(
                                running.get().incidentKey(),
                                invocation.investigationId(),
                                invocation.id(),
                                observationId,
                                draft.kind().name(),
                                running.get().resourceKey()),
                        Correlation.currentId()));
            }
            return Optional.of(List.copyOf(created));
        });
    }

    /** @return 是否由本次记为 FAILED；已不是 RUNNING 时不写 */
    public boolean recordFailed(long invocationId, ErrorCode errorCode, String safeMessage) {
        String sanitized = sanitizer.sanitize(safeMessage);
        String message = sanitized.codePointCount(0, sanitized.length()) > MESSAGE_MAX
                ? sanitized.substring(0, sanitized.offsetByCodePoints(0, MESSAGE_MAX))
                : sanitized;
        return transaction.execute(status -> {
            Optional<Locked> running = lockRunning(invocationId);
            if (running.isEmpty()) {
                return false;
            }
            InvocationRecord invocation = running.get().invocation();
            Instant now = now();
            if (!invocations.markFailed(invocationId, errorCode, message, now, durationMillis(invocation, now))) {
                return false;
            }
            timeline.append(new NewTimelineEvent(
                    invocation.incidentId(),
                    TimelineEventType.CAPABILITY_FAILED,
                    now,
                    TimelineActorType.SYSTEM,
                    null,
                    invocation.capabilityKey() + "（" + running.get().resourceKey() + "）调用失败：" + errorCode.name(),
                    new CapabilityFailedPayloadV1(
                            running.get().incidentKey(),
                            invocation.investigationId(),
                            invocation.id(),
                            invocation.capabilityKey(),
                            running.get().resourceKey(),
                            errorCode.name()),
                    Correlation.currentId()));
            return true;
        });
    }

    /** 已锁定的 RUNNING 调用及写时间线所需的键。 */
    private record Locked(InvocationRecord invocation, String incidentKey, String resourceKey) {}

    /**
     * 锁序 Incident → Invocation（与准入、Stop 同样先锁 Incident；时间线追加要求持有 Incident 行锁，04 §57）。incident_id 创建后不变，
     * 先不加锁读取。已不是 RUNNING 时为空。
     */
    private Optional<Locked> lockRunning(long invocationId) {
        long incidentId = invocations
                .findIncidentId(invocationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown capability invocation: " + invocationId));
        Incident incident = incidents
                .findByIdForUpdate(incidentId)
                .orElseThrow(() -> new IllegalStateException("Invocation without incident: " + invocationId));
        InvocationRecord invocation = invocations
                .findByIdForUpdate(invocationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown capability invocation: " + invocationId));
        if (!invocation.isRunning()) {
            return Optional.empty();
        }
        String resourceKey = resources
                .findById(invocation.managedResourceId())
                .map(ManagedResource::resourceKey)
                .orElseThrow(() -> new IllegalStateException("Invocation without resource: " + invocationId));
        return Optional.of(new Locked(invocation, incident.incidentKey().value(), resourceKey));
    }

    private static long durationMillis(InvocationRecord invocation, Instant finishedAt) {
        return Math.max(0, Duration.between(invocation.startedAt(), finishedAt).toMillis());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
