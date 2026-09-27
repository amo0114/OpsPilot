package io.github.ismoyuan.opspilot.domain.incident;

import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.AWAITING_APPROVAL;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.CANCELLED;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.CREATED;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.DIAGNOSED;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.EXECUTING;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.INVESTIGATING;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.RESOLVED;
import static io.github.ismoyuan.opspilot.domain.incident.IncidentStatus.VERIFYING;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Incident 主状态机的唯一定义（01 §4、07 §34）：(from, trigger) → to。纯规则，不访问数据库；
 * 持久化只能通过由本策略验证过的 {@link IncidentTransition} 条件更新（07 §35）。
 */
public final class IncidentTransitionPolicy {

    private static final Map<IncidentTrigger, Map<IncidentStatus, IncidentStatus>> EDGES =
            new EnumMap<>(IncidentTrigger.class);

    static {
        edge(IncidentTrigger.START_INVESTIGATION, CREATED, INVESTIGATING);
        edge(IncidentTrigger.CONTINUE_INVESTIGATION, DIAGNOSED, INVESTIGATING);
        edge(IncidentTrigger.COMPLETE_INVESTIGATION, INVESTIGATING, DIAGNOSED);
        edge(IncidentTrigger.REQUEST_APPROVAL, DIAGNOSED, AWAITING_APPROVAL);
        edge(IncidentTrigger.VERIFY_RECOVERY, DIAGNOSED, VERIFYING);
        edge(IncidentTrigger.REJECT_APPROVAL, AWAITING_APPROVAL, DIAGNOSED);
        edge(IncidentTrigger.CANCEL_APPROVAL, AWAITING_APPROVAL, DIAGNOSED);
        edge(IncidentTrigger.START_EXECUTION, AWAITING_APPROVAL, EXECUTING);
        edge(IncidentTrigger.EXECUTION_FAILED, EXECUTING, DIAGNOSED);
        edge(IncidentTrigger.EXECUTION_SUCCEEDED, EXECUTING, VERIFYING);
        edge(IncidentTrigger.VERIFICATION_PASSED, VERIFYING, RESOLVED);
        edge(IncidentTrigger.VERIFICATION_FAILED, VERIFYING, INVESTIGATING);
        edge(IncidentTrigger.VERIFICATION_INCONCLUSIVE, VERIFYING, DIAGNOSED);
        // EXECUTING、VERIFYING 不可中途取消；终态不可再迁移（01 §4、§32～§33）
        for (IncidentStatus from : EnumSet.of(CREATED, INVESTIGATING, DIAGNOSED, AWAITING_APPROVAL)) {
            edge(IncidentTrigger.CANCEL_INCIDENT, from, CANCELLED);
        }
    }

    private IncidentTransitionPolicy() {}

    private static void edge(IncidentTrigger trigger, IncidentStatus from, IncidentStatus to) {
        EDGES.computeIfAbsent(trigger, key -> new EnumMap<>(IncidentStatus.class))
                .put(from, to);
    }

    /** 不允许时为空。 */
    public static Optional<IncidentStatus> target(IncidentStatus from, IncidentTrigger trigger) {
        return Optional.ofNullable(EDGES.getOrDefault(trigger, Map.of()).get(from));
    }

    /** 该触发允许的来源状态，用于冲突响应的 expectedStatuses（05 §9）。 */
    public static Set<IncidentStatus> allowedSources(IncidentTrigger trigger) {
        return Set.copyOf(EDGES.getOrDefault(trigger, Map.of()).keySet());
    }
}
