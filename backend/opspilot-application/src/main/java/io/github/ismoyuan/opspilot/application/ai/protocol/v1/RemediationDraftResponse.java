package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/**
 * AI Runtime → Java 的处理建议（05 §88）：只是 Proposal。riskLevel、requiresApproval 由 Java 确定性策略产生，
 * RecoveryPolicy 与容器/执行上下文不属于 AI 输出，出现即作为未知字段拒绝。目标是否在 allowedActions 中由 Java 校验（TASK-064）。
 */
public record RemediationDraftResponse(
        int protocolVersion, String correlationId, RemediationIntentType intentType, Proposal proposal) {

    public RemediationDraftResponse {
        ProtocolChecks.protocolVersion(protocolVersion);
        ProtocolChecks.correlationId(correlationId);
        ProtocolChecks.required("intentType", intentType);
        ProtocolChecks.required("proposal", proposal);
    }

    public enum RemediationIntentType {
        PROPOSE_REMEDIATION
    }

    /** 上限为本批协议取值，Plan 表（TASK-062）沿用。 */
    public record Proposal(String title, String summary, Action action) {

        public Proposal {
            ProtocolChecks.text("title", title, 200);
            ProtocolChecks.text("summary", summary, 2000);
            ProtocolChecks.required("action", action);
        }
    }

    public record Action(
            String capabilityKey,
            long targetResourceId,
            ServiceRestartParametersV1 parameters,
            String summary,
            String expectedImpactSummary) {

        public static final String CAPABILITY_KEY = "service.restart";

        public Action {
            ProtocolChecks.constant("capabilityKey", CAPABILITY_KEY, capabilityKey);
            ProtocolChecks.id("targetResourceId", targetResourceId);
            ProtocolChecks.required("parameters", parameters);
            ProtocolChecks.text("summary", summary, 500);
            ProtocolChecks.text("expectedImpactSummary", expectedImpactSummary, 1000);
        }
    }
}
