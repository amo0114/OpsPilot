package io.github.ismoyuan.opspilot.domain.investigation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

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
}
