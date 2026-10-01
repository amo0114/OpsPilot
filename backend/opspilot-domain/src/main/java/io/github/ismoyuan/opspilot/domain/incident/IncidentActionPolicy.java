package io.github.ismoyuan.opspilot.domain.incident;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前真实状态下可提供的动作（08 TASK-086、05 §12～§13、§91）：唯一权威在 Java，前端只按结果显示，不复制状态机。状态前置条件直接取自
 * {@link IncidentTransitionPolicy} 的边，其余条件与各动作写路径的拒绝规则一致：Stop 只在 INVESTIGATING 且本轮尚未请求停止；Continue
 * 不能在存在 PENDING Approval 时进行（05 §28）；请求处理建议要求最新 Diagnosis 可操作且有可用写动作（05 §29～§30）。纯规则，不访问
 * 数据库；事实由调用方在同一一致性读取中取得。动作是否最终成功仍由写路径在 Incident 行锁下以 expectedVersion 复核。
 */
public final class IncidentActionPolicy {

    private IncidentActionPolicy() {}

    /**
     * @param stopRequested 当前 run 是否已请求停止（尚未开始调查为 false）
     * @param pendingApproval 是否存在 PENDING Approval
     * @param remediationAvailable 最新 Diagnosis 是 PRIMARY_CAUSE_IDENTIFIED／POSSIBLE_CAUSE 且至少有一个可用写动作
     * @return 按 {@link IncidentAction} 声明顺序
     */
    public static List<IncidentAction> available(
            IncidentStatus status, boolean stopRequested, boolean pendingApproval, boolean remediationAvailable) {
        List<IncidentAction> actions = new ArrayList<>();
        if (allows(status, IncidentTrigger.START_INVESTIGATION)) {
            actions.add(IncidentAction.START_INVESTIGATION);
        }
        if (status == IncidentStatus.INVESTIGATING && !stopRequested) {
            actions.add(IncidentAction.STOP_INVESTIGATION);
        }
        if (allows(status, IncidentTrigger.CONTINUE_INVESTIGATION) && !pendingApproval) {
            actions.add(IncidentAction.CONTINUE_INVESTIGATION);
        }
        if (allows(status, IncidentTrigger.REQUEST_APPROVAL) && remediationAvailable) {
            actions.add(IncidentAction.REQUEST_REMEDIATION);
        }
        if (allows(status, IncidentTrigger.VERIFY_RECOVERY)) {
            actions.add(IncidentAction.VERIFY_RECOVERY);
        }
        if (allows(status, IncidentTrigger.CANCEL_INCIDENT)) {
            actions.add(IncidentAction.CANCEL_INCIDENT);
        }
        return List.copyOf(actions);
    }

    private static boolean allows(IncidentStatus status, IncidentTrigger trigger) {
        return IncidentTransitionPolicy.target(status, trigger).isPresent();
    }
}
