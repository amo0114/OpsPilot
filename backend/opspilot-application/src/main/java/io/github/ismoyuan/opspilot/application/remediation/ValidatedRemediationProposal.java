package io.github.ismoyuan.opspilot.application.remediation;

import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import io.github.ismoyuan.opspilot.domain.capability.RiskLevel;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import java.util.Objects;

/**
 * 经 Java 校验、可据以创建 Plan / Action / Approval 的处理建议（08 TASK-064、04 §41）：动作来自当时的 allowedActions，参数为强类型
 * 载荷的规范 JSON，riskLevel 与 requiresApproval 由 Java Registry 产生，不来自 AI（05 §88、06 §13）。
 *
 * @param diagnosisId Plan 所基于的 Diagnosis
 */
public record ValidatedRemediationProposal(
        long diagnosisId,
        String title,
        String summary,
        String capabilityKey,
        ManagedResource target,
        CapabilitySchema parameterSchema,
        String parameterPayload,
        String actionSummary,
        String expectedImpactSummary,
        RiskLevel riskLevel,
        boolean requiresApproval) {

    public ValidatedRemediationProposal {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(parameterSchema, "parameterSchema");
        Objects.requireNonNull(parameterPayload, "parameterPayload");
        Objects.requireNonNull(actionSummary, "actionSummary");
        Objects.requireNonNull(expectedImpactSummary, "expectedImpactSummary");
        Objects.requireNonNull(riskLevel, "riskLevel");
    }
}
