package io.github.ismoyuan.opspilot.application.approval;

/**
 * 某 Incident 是否存在 PENDING Approval（05 §28）：继续调查要求不存在待审批方案。调用方已持有 Incident 行锁，结果与
 * AWAITING_APPROVAL 状态互为印证（01 §3.4）。
 */
public interface PendingApprovalQuery {

    boolean existsPending(long incidentId);
}
