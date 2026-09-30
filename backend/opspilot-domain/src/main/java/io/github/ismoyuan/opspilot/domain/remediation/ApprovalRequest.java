package io.github.ismoyuan.opspilot.domain.remediation;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 一条审批（01 §24～§25、04 §43～§44）：决定只从 PENDING 前进到 APPROVED / REJECTED / CANCELLED，记录决定人、时间与说明，不可反转。
 * V0.1 允许自审批，但决定仍须留痕。
 *
 * @param version 乐观锁版本（lock_version），每次决定加一
 */
public record ApprovalRequest(
        long id,
        long remediationActionId,
        ApprovalStatus status,
        Instant requestedAt,
        String decidedBy,
        Instant decidedAt,
        String comment,
        long version) {

    /** 与 approval_request.comment 列一致（本批取值，与取消原因上限相同）。 */
    public static final int COMMENT_MAX = 500;

    public ApprovalRequest {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (status.isDecided() != (decidedBy != null && decidedAt != null)) {
            throw new IllegalArgumentException("decision fields must be present exactly when decided");
        }
    }

    /**
     * @param target 终态
     * @throws DomainException APPROVAL_ALREADY_DECIDED：已不是 PENDING
     */
    public ApprovalRequest decide(ApprovalStatus target, String actor, String decisionComment, Instant at) {
        if (!target.isDecided()) {
            throw new IllegalArgumentException("A decision must be terminal: " + target);
        }
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(at, "at");
        if (status.isDecided()) {
            throw alreadyDecided();
        }
        return new ApprovalRequest(
                id, remediationActionId, target, requestedAt, actor, at, decisionComment, version + 1);
    }

    /** 05 §43：同一操作者对同一 Approval 重复提交相同决定与原 comment。 */
    public boolean isSameDecision(ApprovalStatus target, String actor, String decisionComment) {
        return status == target && Objects.equals(decidedBy, actor) && Objects.equals(comment, decisionComment);
    }

    public DomainException alreadyDecided() {
        return new DomainException(
                ErrorCode.APPROVAL_ALREADY_DECIDED,
                "Approval already decided",
                Map.of("approvalId", id, "status", status.name()));
    }
}
