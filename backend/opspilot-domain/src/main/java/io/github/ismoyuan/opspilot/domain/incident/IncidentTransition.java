package io.github.ismoyuan.opspilot.domain.incident;

import java.util.Objects;

/**
 * 一次经 {@link IncidentTransitionPolicy} 验证的状态迁移，是持久化条件更新的唯一输入：
 * WHERE id = incidentId AND status = expectedStatus AND lock_version = expectedVersion（04 §14、07 §35）。
 * 构造时再次核对策略，非法组合无法构造。
 */
public record IncidentTransition(
        long incidentId,
        IncidentTrigger trigger,
        IncidentStatus expectedStatus,
        long expectedVersion,
        IncidentStatus targetStatus) {

    public IncidentTransition {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(targetStatus, "targetStatus");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be >= 0");
        }
        if (IncidentTransitionPolicy.target(expectedStatus, trigger)
                .filter(targetStatus::equals)
                .isEmpty()) {
            throw new IllegalArgumentException(
                    "transition not allowed: " + expectedStatus + " --" + trigger + "--> " + targetStatus);
        }
    }
}
