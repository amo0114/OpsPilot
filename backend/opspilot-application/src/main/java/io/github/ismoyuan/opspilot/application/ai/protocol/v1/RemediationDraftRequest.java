package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import java.util.List;

/**
 * Java → AI Runtime 的处理建议请求（05 §87）：只针对 PRIMARY/POSSIBLE 诊断，allowedActions 是 AI 唯一可选范围。
 * 不含 RecoveryPolicy、容器身份或凭证（05 §88）。
 */
public record RemediationDraftRequest(
        int protocolVersion,
        String correlationId,
        Incident incident,
        Diagnosis diagnosis,
        List<AllowedAction> allowedActions) {

    public RemediationDraftRequest {
        ProtocolChecks.protocolVersion(protocolVersion);
        ProtocolChecks.correlationId(correlationId);
        ProtocolChecks.required("incident", incident);
        ProtocolChecks.required("diagnosis", diagnosis);
        allowedActions = ProtocolChecks.uniqueList("allowedActions", allowedActions, 1, Integer.MAX_VALUE);
    }

    public record Incident(String incidentKey, String impactSummary) {

        public Incident {
            ProtocolChecks.incidentKey(incidentKey);
            ProtocolChecks.text("impactSummary", impactSummary, 1000);
        }
    }

    /** UNDETERMINED 不能生成写操作方案（01 §20）。 */
    public record Diagnosis(
            int version, DiagnosisConclusionType conclusionType, String summary, List<EvidenceSummary> evidence) {

        public Diagnosis {
            ProtocolChecks.positive("version", version);
            if (ProtocolChecks.required("conclusionType", conclusionType) == DiagnosisConclusionType.UNDETERMINED) {
                throw ProtocolChecks.invalid("conclusionType");
            }
            ProtocolChecks.text("summary", summary, 2000);
            evidence = ProtocolChecks.list("evidence", evidence);
            if (evidence.isEmpty()) {
                throw ProtocolChecks.invalid("evidence");
            }
        }
    }

    public record EvidenceSummary(long id, String summary) {

        public EvidenceSummary {
            ProtocolChecks.id("id", id);
            ProtocolChecks.text("summary", summary, 1000);
        }
    }

    /** V0.1 唯一写能力 service.restart（06 §101～§105）。 */
    public record AllowedAction(String capabilityKey, long resourceId, String resourceKey, String resourceName) {

        public AllowedAction {
            ProtocolChecks.constant("capabilityKey", RemediationDraftResponse.Action.CAPABILITY_KEY, capabilityKey);
            ProtocolChecks.id("resourceId", resourceId);
            ProtocolChecks.resourceKey(resourceKey);
            ProtocolChecks.text("resourceName", resourceName, 128);
        }
    }
}
