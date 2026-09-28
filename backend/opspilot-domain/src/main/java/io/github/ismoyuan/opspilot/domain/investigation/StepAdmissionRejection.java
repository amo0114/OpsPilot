package io.github.ismoyuan.opspilot.domain.investigation;

import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import java.util.Optional;

/**
 * 单步准入被拒绝的原因（07 §42、08 TASK-039）。前两种表示这份工作已不属于当前调查，Worker 直接退出；其余是本轮的确定性退出条件
 * （01 §11），对应收束原因由 TASK-042 使用。
 */
public enum StepAdmissionRejection {
    NOT_INVESTIGATING(null),
    STALE_RUN(null),
    STOP_REQUESTED(TerminationReason.USER_STOPPED),
    DEADLINE_REACHED(TerminationReason.INVESTIGATION_TIMEOUT),
    CAPABILITY_BUDGET_EXHAUSTED(TerminationReason.CAPABILITY_BUDGET_EXHAUSTED),
    AI_FAILURE_THRESHOLD_REACHED(TerminationReason.AI_RUNTIME_UNAVAILABLE);

    private final TerminationReason terminationReason;

    StepAdmissionRejection(TerminationReason terminationReason) {
        this.terminationReason = terminationReason;
    }

    /** 本轮应当收束时的原因；工作已过期（非调查中或旧 run）时为空。 */
    public Optional<TerminationReason> terminationReason() {
        return Optional.ofNullable(terminationReason);
    }
}
