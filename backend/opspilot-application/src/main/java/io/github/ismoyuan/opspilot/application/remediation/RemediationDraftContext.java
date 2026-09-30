package io.github.ismoyuan.opspilot.application.remediation;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftRequest;
import java.util.List;
import java.util.Objects;

/**
 * 一次处理建议请求的快照（08 TASK-063）：发给 AI 的请求，以及事务外调用 AI 之后重新校验（TASK-064/065）所需的身份——Incident 版本、
 * 当前 Diagnosis 与当时允许的动作。
 */
public record RemediationDraftContext(
        long incidentId,
        long managedSystemId,
        long incidentVersion,
        long diagnosisId,
        RemediationDraftRequest request,
        List<AllowedRemediationAction> allowedActions) {

    public RemediationDraftContext {
        Objects.requireNonNull(request, "request");
        allowedActions = List.copyOf(allowedActions);
        if (allowedActions.isEmpty()) {
            throw new IllegalArgumentException("A remediation draft needs at least one allowed action");
        }
    }
}
