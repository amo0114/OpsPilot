package io.github.ismoyuan.opspilot.application.investigation.step;

import io.github.ismoyuan.opspilot.application.ai.InvestigationStepDecision;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRepository;
import io.github.ismoyuan.opspilot.domain.agentstep.AgentStep;
import io.github.ismoyuan.opspilot.domain.agentstep.AgentStepStatus;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.investigation.Investigation;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 记录一次 AI 调用的结果（08 TASK-038、07 §41、02 §28）。一个短事务内按 Incident → Investigation → Step 加锁，把 RUNNING 的 Step
 * 条件写为 SUCCEEDED/FAILED，并只对仍是当前 run 的 Step 维护连续 AI 失败计数：合法输出清零，连接失败、超时、输出非法加一，
 * 其他失败不改变计数。
 * 迟到输出（Incident 已不在调查或 run 已切换）照常审计，但返回非当前，不改新 run 的计数，也不允许驱动领域写入（BND-015）。
 *
 * <p>协议回显与已登记身份不符的结果按 AI_OUTPUT_INVALID 失败记录。本类不处理 Intent（TASK-040），也不处理进程中断
 * （PROCESS_INTERRUPTED 由启动恢复直接写入且不计入连续失败，TASK-043）。
 */
@Service
public class AgentStepRecorder {

    /** 计入模型自身连续失败的错误（02 §28、§31）。 */
    static final Set<ErrorCode> AI_FAILURES =
            Set.of(ErrorCode.AI_RUNTIME_UNAVAILABLE, ErrorCode.AI_RUNTIME_TIMEOUT, ErrorCode.AI_OUTPUT_INVALID);

    /** 与 agent_step_record.error_message 列长度一致。 */
    static final int MESSAGE_MAX = 1000;

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final AgentStepRepository steps;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AgentStepRecorder(
            IncidentRepository incidents,
            InvestigationRepository investigations,
            AgentStepRepository steps,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.steps = steps;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 已通过协议校验的结果；回显不符则按 AI_OUTPUT_INVALID 失败记录。 */
    public StepOutcome recordDecision(long stepId, InvestigationStepDecision decision, long latencyMs) {
        return transaction.execute(status -> {
            Locked locked = lock(stepId);
            InvestigationStepResponse response = decision.response();
            if (response.stepId() != stepId || response.runNo() != locked.step().runNo()) {
                Instant now = now();
                steps.markFailed(
                        locked.step(),
                        ErrorCode.AI_OUTPUT_INVALID,
                        "AI step echo does not match the registered step",
                        latencyMs,
                        now);
                return count(locked, FailureEffect.COUNT, now);
            }
            Instant now = now();
            steps.markSucceeded(locked.step(), response, decision.metadata(), latencyMs, now);
            return count(locked, FailureEffect.RESET, now);
        });
    }

    /**
     * @param safeMessage 固定文案（如 ApplicationException 的内部说明），不得包含 AI 输出、请求内容或凭证
     */
    public StepOutcome recordFailure(long stepId, ErrorCode errorCode, String safeMessage, long latencyMs) {
        String message = safeMessage == null || safeMessage.isBlank() ? errorCode.defaultMessage() : safeMessage;
        if (message.codePointCount(0, message.length()) > MESSAGE_MAX) {
            message = message.substring(0, message.offsetByCodePoints(0, MESSAGE_MAX));
        }
        String stored = message;
        return transaction.execute(status -> {
            Locked locked = lock(stepId);
            Instant now = now();
            steps.markFailed(locked.step(), errorCode, stored, latencyMs, now);
            return count(locked, AI_FAILURES.contains(errorCode) ? FailureEffect.COUNT : FailureEffect.KEEP, now);
        });
    }

    private record Locked(Incident incident, Investigation investigation, AgentStep step) {}

    /** 合法输出清零；模型自身失败加一；其他失败（如本端请求不合协议）不改变计数。 */
    private enum FailureEffect {
        RESET,
        COUNT,
        KEEP
    }

    private Locked lock(long stepId) {
        // 先无锁读取不可变的 incidentId，再按 Incident → Investigation → Step 加锁，与准入、Stop 同一锁序：
        // 若先锁 Step 再等 Incident，会与持有 Incident 后分配 step_no 的准入事务死锁
        long incidentId = steps.findIncidentId(stepId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent step: " + stepId));
        Incident incident = incidents
                .findByIdForUpdate(incidentId)
                .orElseThrow(() -> new IllegalStateException("Agent step without incident: " + stepId));
        Investigation investigation = investigations
                .findByIncidentIdForUpdate(incident.id())
                .orElseThrow(() -> new IllegalStateException("Agent step without investigation: " + stepId));
        AgentStep step = steps.findByIdForUpdate(stepId).orElseThrow();
        if (step.status() != AgentStepStatus.RUNNING) {
            throw new IllegalStateException("Agent step already finished: " + stepId);
        }
        return new Locked(incident, investigation, step);
    }

    private StepOutcome count(Locked locked, FailureEffect effect, Instant now) {
        Investigation investigation = locked.investigation();
        boolean current = locked.incident().status() == IncidentStatus.INVESTIGATING
                && investigation.currentRunNo() == locked.step().runNo();
        if (!current) {
            return new StepOutcome(false, investigation.stopRequested(), investigation.consecutiveAiFailureCount());
        }
        Investigation updated = switch (effect) {
            case RESET -> investigation.withAiStepSuccess();
            case COUNT -> investigation.withAiStepFailure();
            case KEEP -> investigation;
        };
        if (updated.consecutiveAiFailureCount() != investigation.consecutiveAiFailureCount()) {
            updated = investigations.saveAiFailureCount(investigation, updated, now);
        }
        return new StepOutcome(true, updated.stopRequested(), updated.consecutiveAiFailureCount());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
