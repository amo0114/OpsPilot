package io.github.ismoyuan.opspilot.domain.investigation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 一个 Incident 唯一的调查工作空间（03 §19～§20、04 §16）。运行周期是其中的逻辑序号，不是独立实体；
 * 是否允许调查以 Incident.status 为准（01 §10）。
 *
 * @param stopRequestedAt 当前轮协作式停止意图，可为空；与 stopRequestedBy 同空同非空
 * @param version 对应 lock_version
 */
public record Investigation(
        long id,
        long incidentId,
        Instant startedAt,
        Instant lastActivityAt,
        int currentRunNo,
        Instant currentRunStartedAt,
        int currentRunCapabilityCount,
        long capabilityCallCount,
        int consecutiveAiFailureCount,
        Instant stopRequestedAt,
        String stopRequestedBy,
        InvestigationLimits limits,
        long version) {

    public Investigation {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(lastActivityAt, "lastActivityAt");
        Objects.requireNonNull(currentRunStartedAt, "currentRunStartedAt");
        Objects.requireNonNull(limits, "limits");
        if (currentRunNo < 1) {
            throw new IllegalArgumentException("currentRunNo must be >= 1");
        }
        if ((stopRequestedAt == null) != (stopRequestedBy == null)) {
            throw new IllegalArgumentException("stop time and actor must be set together");
        }
    }

    /** 本轮截止时间：current_run_started_at + max_duration_seconds（01 §9）。 */
    public Instant currentRunDeadline() {
        return currentRunStartedAt.plus(Duration.ofSeconds(limits.maxDurationSeconds()));
    }

    public boolean stopRequested() {
        return stopRequestedAt != null;
    }

    /**
     * 当前 run 的协作式停止意图（05 §27）：只写停止时间与身份，不改变轮号、计数或 Incident 状态。
     */
    public Investigation withStopRequested(Instant at, String actor) {
        if (stopRequested()) {
            throw new IllegalStateException("stop already requested for run " + currentRunNo);
        }
        return new Investigation(
                id,
                incidentId,
                startedAt,
                lastActivityAt,
                currentRunNo,
                currentRunStartedAt,
                currentRunCapabilityCount,
                capabilityCallCount,
                consecutiveAiFailureCount,
                at,
                Objects.requireNonNull(actor, "actor"),
                limits,
                version);
    }

    /**
     * 显式进入下一轮（resumeInvestigation，01 §9）：轮号加一，只重置本轮起点、本轮计数、连续 AI 失败与 Stop；
     * 首次开始时间、累计调用数与限制快照不变。应用重启不得调用（07 §52）。
     */
    public Investigation nextRun(Instant now) {
        return new Investigation(
                id,
                incidentId,
                startedAt,
                now,
                currentRunNo + 1,
                now,
                0,
                capabilityCallCount,
                0,
                null,
                null,
                limits,
                version);
    }
    /**
     * 单步准入规则（07 §42、08 TASK-039），按顺序给出第一个拒绝原因；Incident 是否 INVESTIGATING 由调用方在同一锁内先查。
     * 本轮截止时刻本身已不可准入。
     */
    public Optional<StepAdmissionRejection> checkStepAdmission(int expectedRunNo, Instant now) {
        if (expectedRunNo != currentRunNo) {
            return Optional.of(StepAdmissionRejection.STALE_RUN);
        }
        if (stopRequested()) {
            return Optional.of(StepAdmissionRejection.STOP_REQUESTED);
        }
        if (!now.isBefore(currentRunDeadline())) {
            return Optional.of(StepAdmissionRejection.DEADLINE_REACHED);
        }
        if (currentRunCapabilityCount >= limits.maxCapabilityCalls()) {
            return Optional.of(StepAdmissionRejection.CAPABILITY_BUDGET_EXHAUSTED);
        }
        if (consecutiveAiFailureCount >= limits.maxConsecutiveAiFailures()) {
            return Optional.of(StepAdmissionRejection.AI_FAILURE_THRESHOLD_REACHED);
        }
        return Optional.empty();
    }

    /**
     * 调查 OBSERVE 调用的准入规则（07 §42、§56）：与单步准入相同的 run、Stop、截止与本轮额度检查，不含连续 AI 失败阈值。
     */
    public Optional<StepAdmissionRejection> checkCapabilityAdmission(int expectedRunNo, Instant now) {
        return checkStepAdmission(expectedRunNo, now)
                .filter(reason -> reason != StepAdmissionRejection.AI_FAILURE_THRESHOLD_REACHED);
    }

    /** 本次 AI 调用最多等待：单步超时与本轮剩余时间的较小值（05 §89）；只在准入通过后使用。 */
    public Duration stepWaitLimit(Instant now) {
        Duration remaining = Duration.between(now, currentRunDeadline());
        Duration step = Duration.ofSeconds(limits.agentStepTimeoutSeconds());
        return remaining.compareTo(step) < 0 ? remaining : step;
    }

    /** 本轮一次 AI 失败（连接、超时、输出非法，02 §28）；进程中断不经此计数。 */
    public Investigation withAiStepFailure() {
        return withConsecutiveAiFailures(consecutiveAiFailureCount + 1);
    }

    /** 本轮取得合法输出后清零（02 §28）。 */
    public Investigation withAiStepSuccess() {
        return withConsecutiveAiFailures(0);
    }

    private Investigation withConsecutiveAiFailures(int count) {
        return new Investigation(
                id,
                incidentId,
                startedAt,
                lastActivityAt,
                currentRunNo,
                currentRunStartedAt,
                currentRunCapabilityCount,
                capabilityCallCount,
                count,
                stopRequestedAt,
                stopRequestedBy,
                limits,
                version);
    }
}
