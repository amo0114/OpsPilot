package io.github.ismoyuan.opspilot.infrastructure.approval;

import io.github.ismoyuan.opspilot.application.approval.PendingApprovalCanceller;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 审批与方案表建立前的占位实现：故意失败，使取消事务整体回滚，而不是在未撤销审批的情况下把 Incident 标为 CANCELLED。
 * 当前没有任何用例能使 Incident 进入 AWAITING_APPROVAL；TASK-062/066 建表、TASK-067 以真实实现替换本类并补集成验证。
 */
@Component
class UnavailablePendingApprovalCanceller implements PendingApprovalCanceller {

    @Override
    public void cancelPendingApprovalAndPlans(long incidentId, Instant at, String actor) {
        throw new IllegalStateException(
                "Pending approval cancellation is not available before TASK-067: incidentId=" + incidentId);
    }
}
