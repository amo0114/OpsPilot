package io.github.ismoyuan.opspilot.web.remediation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 05 §38、§41、§42 请求体。
 *
 * @param comment 可为空
 */
public record ApprovalDecisionRequest(
        @NotNull @PositiveOrZero Long expectedApprovalVersion,
        @NotNull @PositiveOrZero Long expectedIncidentVersion,
        String comment) {}
