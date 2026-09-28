package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.observation.ObservationRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.observation.NewObservation;
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
 */
@Service
public class CapabilityResultRecorder {

    /** 与 capability_invocation.error_message 列长度一致。 */
    static final int MESSAGE_MAX = 1000;

    private final CapabilityInvocationRepository invocations;
    private final ObservationRepository observations;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CapabilityResultRecorder(
            CapabilityInvocationRepository invocations,
            ObservationRepository observations,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.invocations = invocations;
        this.observations = observations;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** @return 产生的 Observation id；Invocation 已不是 RUNNING 时为空且不写任何数据 */
    public Optional<List<Long>> recordSucceeded(long invocationId, InvocationOutcome.Succeeded outcome) {
        return transaction.execute(status -> {
            Optional<InvocationRecord> running = lockRunning(invocationId);
            if (running.isEmpty()) {
                return Optional.<List<Long>>empty();
            }
            InvocationRecord invocation = running.get();
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
                created.add(observations
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
                        .id());
            }
            return Optional.of(List.copyOf(created));
        });
    }

    /** @return 是否由本次记为 FAILED；已不是 RUNNING 时不写 */
    public boolean recordFailed(long invocationId, ErrorCode errorCode, String safeMessage) {
        String message = safeMessage.codePointCount(0, safeMessage.length()) > MESSAGE_MAX
                ? safeMessage.substring(0, safeMessage.offsetByCodePoints(0, MESSAGE_MAX))
                : safeMessage;
        return transaction.execute(status -> {
            Optional<InvocationRecord> running = lockRunning(invocationId);
            if (running.isEmpty()) {
                return false;
            }
            Instant now = now();
            return invocations.markFailed(invocationId, errorCode, message, now, durationMillis(running.get(), now));
        });
    }

    private Optional<InvocationRecord> lockRunning(long invocationId) {
        InvocationRecord invocation = invocations
                .findByIdForUpdate(invocationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown capability invocation: " + invocationId));
        return invocation.isRunning() ? Optional.of(invocation) : Optional.empty();
    }

    private static long durationMillis(InvocationRecord invocation, Instant finishedAt) {
        return Math.max(0, Duration.between(invocation.startedAt(), finishedAt).toMillis());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
