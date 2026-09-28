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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

    /**
     * 记录已通过协议校验的结果并在同一事务内处置它（08 TASK-040～041、07 §41、§43）。回显不符按 AI_OUTPUT_INVALID 失败记录。
     * 处置规则在持锁后判定：不是当前 run 或 Incident 已不在调查 → NOT_CURRENT；同轮已 Stop 且不是 COMPLETE_INVESTIGATION
     * → STOPPED；两者都只审计。其余交给 {@code applier}，其领域写入与 Step 审计一起提交。
     */
    public StepDecisionOutcome recordDecision(
            long stepId, InvestigationStepDecision decision, long latencyMs, IntentApplier applier) {
        long incidentId = incidentOf(stepId);
        return transaction.execute(status -> {
            Locked locked = lock(stepId, incidentId);
            InvestigationStepResponse response = decision.response();
            if (response.stepId() != stepId || response.runNo() != locked.step().runNo()) {
                Instant now = now();
                steps.markFailed(
                        locked.step(),
                        ErrorCode.AI_OUTPUT_INVALID,
                        "AI step echo does not match the registered step",
                        latencyMs,
                        now);
                return new StepDecisionOutcome(
                        count(locked, FailureEffect.COUNT, now),
                        IntentDisposition.rejected(ErrorCode.AI_OUTPUT_INVALID.name(), "ECHO_MISMATCH"));
            }
            IntentDisposition disposition = dispose(locked, response, applier);
            Instant now = now();
            steps.markSucceeded(
                    locked.step(), new AgentStepOutput(response, disposition), decision.metadata(), latencyMs, now);
            return new StepDecisionOutcome(count(locked, FailureEffect.RESET, now), disposition);
        });
    }

    private static IntentDisposition dispose(Locked locked, InvestigationStepResponse response, IntentApplier applier) {
        Investigation investigation = locked.investigation();
        if (!current(locked)) {
            return IntentDisposition.of(IntentDisposition.Outcome.NOT_CURRENT);
        }
        if (investigation.stopRequested()
                && !(response instanceof InvestigationStepResponse.CompleteInvestigationStep)) {
            return IntentDisposition.of(IntentDisposition.Outcome.STOPPED);
        }
        return applier.apply(
                new ActiveStep(
                        locked.step().id(),
                        locked.incident().id(),
                        investigation.id(),
                        investigation.currentRunNo(),
                        investigation.currentRunStartedAt()),
                response);
    }

    private static boolean current(Locked locked) {
        return locked.incident().status() == IncidentStatus.INVESTIGATING
                && locked.investigation().currentRunNo() == locked.step().runNo();
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
        long incidentId = incidentOf(stepId);
        return transaction.execute(status -> {
            Locked locked = lock(stepId, incidentId);
            Instant now = now();
            steps.markFailed(locked.step(), errorCode, stored, latencyMs, now);
            return count(locked, AI_FAILURES.contains(errorCode) ? FailureEffect.COUNT : FailureEffect.KEEP, now);
        });
    }

    /** closeUnrecorded 使用的固定文案，不含 AI 输出、请求内容或异常信息。 */
    static final String UNRECORDED_MESSAGE = "AI step outcome could not be recorded";

    /**
     * 结果或失败未能记录时（原事务已整体回滚）以独立短事务终结该 Step（TASK-040/043 修复）。按同一锁序加锁，只处理仍为
     * RUNNING 的这一 Step：记 FAILED/INTERNAL_ERROR（固定文案），不改连续 AI 失败计数，不保存、不重放 AI 输出与 Intent，
     * 不发任何网络请求；已提交的终态保持不变。
     *
     * @return 是否由本次终结；Step 已是终态时为 false
     */
    public boolean closeUnrecorded(long stepId, long latencyMs) {
        long incidentId = incidentOf(stepId);
        return transaction.execute(status -> {
            AgentStep step = lockRows(stepId, incidentId).step();
            if (step.status() != AgentStepStatus.RUNNING) {
                return false;
            }
            steps.markFailed(step, ErrorCode.INTERNAL_ERROR, UNRECORDED_MESSAGE, latencyMs, now());
            return true;
        });
    }

    /**
     * 补完已退出 Worker 留下的 RUNNING Step（TASK-040/043 修复、07 §51）。只能由持有该 Incident Worker 拥有权的调用方在准入新 Step
     * 之前调用：派发器按 Incident 单飞，拥有权在上一个 Worker 返回后才释放，因此此刻该 Incident 没有存活 Worker，这些 Step 的结果都
     * 未能记录（记录失败且当时的收尾也失败，或 Worker 线程异常终止）。旧进程遗留的 RUNNING 由启动恢复先行标为 PROCESS_INTERRUPTED，
     * 且在此之前不会派发，这里不会遇到。只做审计收尾：同一锁序加锁，仍为 RUNNING 的记 FAILED/INTERNAL_ERROR（与 closeUnrecorded
     * 同一文案），不改连续 AI 失败计数，不重放 Intent 或网络调用；失败时抛出，由调用方放弃本次准入。
     *
     * @return 补完的条数
     */
    public int closeOrphanedSteps(long incidentId) {
        List<Long> running = steps.findRunningStepIds(incidentId);
        if (running.isEmpty()) {
            return 0;
        }
        return transaction.execute(status -> {
            int closed = 0;
            for (long stepId : running) {
                AgentStep step = lockRows(stepId, incidentId).step();
                if (step.status() == AgentStepStatus.RUNNING) {
                    Instant now = now();
                    long latency =
                            Math.max(0, Duration.between(step.startedAt(), now).toMillis());
                    steps.markFailed(step, ErrorCode.INTERNAL_ERROR, UNRECORDED_MESSAGE, latency, now);
                    closed++;
                }
            }
            return closed;
        });
    }

    private record Locked(Incident incident, Investigation investigation, AgentStep step) {}

    /** 合法输出清零；模型自身失败加一；其他失败（如本端请求不合协议）不改变计数。 */
    private enum FailureEffect {
        RESET,
        COUNT,
        KEEP
    }

    /**
     * 在结果事务之外无锁读取 Step 所属 Incident（创建后不变）。必须在事务外：事务内的第一次一致性读会固定快照，若发生在取得
     * Incident/Investigation 锁之前，持锁后的普通读（如 Diagnosis 版本号分配，TASK-026）就可能看不到等锁期间他人已提交的数据。
     */
    private long incidentOf(long stepId) {
        return steps.findIncidentId(stepId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent step: " + stepId));
    }

    private Locked lock(long stepId, long incidentId) {
        Locked locked = lockRows(stepId, incidentId);
        if (locked.step().status() != AgentStepStatus.RUNNING) {
            throw new IllegalStateException("Agent step already finished: " + stepId);
        }
        return locked;
    }

    private Locked lockRows(long stepId, long incidentId) {
        // 按 Incident → Investigation → Step 加锁，与准入、Stop 同一锁序：若先锁 Step 再等 Incident，
        // 会与持有 Incident 后分配 step_no 的准入事务死锁；这些加锁读取是本事务最先执行的语句
        Incident incident = incidents
                .findByIdForUpdate(incidentId)
                .orElseThrow(() -> new IllegalStateException("Agent step without incident: " + stepId));
        Investigation investigation = investigations
                .findByIncidentIdForUpdate(incident.id())
                .orElseThrow(() -> new IllegalStateException("Agent step without investigation: " + stepId));
        AgentStep step = steps.findByIdForUpdate(stepId).orElseThrow();
        return new Locked(incident, investigation, step);
    }

    private StepOutcome count(Locked locked, FailureEffect effect, Instant now) {
        Investigation investigation = locked.investigation();
        if (!current(locked)) {
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
