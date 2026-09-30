package io.github.ismoyuan.opspilot.domain.execution;

/**
 * Execution 身份（04 §46～§47、05 §43）：幂等键由已冻结的 Action 身份确定性产生，同一 Action 永远得到同一键；唯一冲突不能靠换键绕过。
 * 格式与 action_execution 的库内约束一致（V005 ck_action_execution_idempotency_key）。
 */
public final class ActionExecutionIdentity {

    private ActionExecutionIdentity() {}

    public static String idempotencyKey(long remediationActionId) {
        if (remediationActionId < 1) {
            throw new IllegalArgumentException("remediationActionId must be positive");
        }
        return "action-execution:" + remediationActionId;
    }
}
