package io.github.ismoyuan.opspilot.application.approval;

import io.github.ismoyuan.opspilot.domain.capability.RiskLevel;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalRequest;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.remediation.RemediationPlanStatus;
import java.time.Instant;
import java.util.Optional;

/** Approval 读写端口（04 §43～§44、§78）。决定只从 PENDING 条件更新；加锁读取须在已持有 Incident 行锁之后。 */
public interface ApprovalRepository {

    /** 不加锁读取 Approval 所属 Incident（创建后不变），供按 Incident → Approval 锁序加锁。 */
    Optional<Long> findIncidentId(long approvalId);

    Optional<LockedApproval> findForUpdate(long approvalId);

    /**
     * PENDING → 终态，条件为仍是 PENDING 且版本等于 {@code before.version()}。
     *
     * @throws IllegalStateException 条件未命中（调用方已持锁，不应发生）
     */
    void saveDecision(ApprovalRequest before, ApprovalRequest after);

    /** 方案 ACTIVE → CANCELLED；已不是 ACTIVE 的不变。 */
    void cancelActivePlan(long planId, Instant at);

    Optional<ApprovalView> findView(long approvalId);

    /** 加锁读取的 Approval 及其方案、动作。 */
    record LockedApproval(
            ApprovalRequest approval,
            long planId,
            RemediationPlanStatus planStatus,
            long diagnosisId,
            String capabilityKey,
            long targetResourceId) {}

    /** 05 §37 待审批内容：具体到 Action 与目标资源。 */
    record ApprovalView(
            long approvalId,
            ApprovalStatus status,
            long version,
            String incidentKey,
            long actionId,
            String actionSummary,
            String capabilityKey,
            String resourceKey,
            String resourceName,
            RiskLevel riskLevel,
            String expectedImpactSummary,
            Instant requestedAt,
            String decidedBy,
            Instant decidedAt,
            String comment) {}
}
