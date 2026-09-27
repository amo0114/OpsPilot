package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;

/** PROPOSE_EVIDENCE_LINK 主 payload（05 §82）；reason 上限与 evidence 列一致。 */
public record ProposeEvidenceLink(long observationId, long hypothesisId, EvidenceRelation relation, String reason) {

    public ProposeEvidenceLink {
        ProtocolChecks.id("observationId", observationId);
        ProtocolChecks.id("hypothesisId", hypothesisId);
        ProtocolChecks.required("relation", relation);
        ProtocolChecks.text("reason", reason, 1000);
    }
}
