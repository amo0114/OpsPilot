package io.github.ismoyuan.opspilot.application.approval;

/**
 * 05 §38、§41、§42 审批决定请求。
 *
 * @param comment 可为空；超过 500 字符拒绝
 */
public record ApprovalDecisionCommand(
        long approvalId, long expectedApprovalVersion, long expectedIncidentVersion, String comment, String actor) {}
