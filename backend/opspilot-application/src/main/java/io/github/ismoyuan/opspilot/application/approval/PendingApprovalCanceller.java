package io.github.ismoyuan.opspilot.application.approval;

import java.time.Instant;

/**
 * 取消等待审批的 Incident 时，同一事务内把 PENDING Approval 与未执行 Plan 置为 CANCELLED 的端口（05 §33、08 TASK-019）。
 * 调用方已持有 Incident 行锁；实现不得留下可批准执行的活动方案。等待审批却没有 PENDING Approval 属数据不一致，实现须失败使事务回滚。
 */
public interface PendingApprovalCanceller {

    void cancelPendingApprovalAndPlans(long incidentId, Instant at, String actor);
}
