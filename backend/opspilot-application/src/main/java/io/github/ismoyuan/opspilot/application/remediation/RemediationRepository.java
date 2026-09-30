package io.github.ismoyuan.opspilot.application.remediation;

import java.time.Instant;

/** 处理方案写入端口（04 §77）：同一事务插入 ACTIVE Plan、其唯一 Action 与 PENDING Approval。调用方已持有 Incident 行锁。 */
public interface RemediationRepository {

    CreatedRemediation insertProposal(long incidentId, ValidatedRemediationProposal proposal, Instant at);

    record CreatedRemediation(long planId, long actionId, long approvalId) {}
}
