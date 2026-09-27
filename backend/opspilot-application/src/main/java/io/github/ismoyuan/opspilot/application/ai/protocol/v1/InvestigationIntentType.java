package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/** 调查阶段允许的五个主 Intent（05 §79、03 §2）；PROPOSE_REMEDIATION 不在其中（BND-014）。 */
public enum InvestigationIntentType {
    REQUEST_CAPABILITY,
    PROPOSE_HYPOTHESIS,
    UPDATE_HYPOTHESIS,
    PROPOSE_EVIDENCE_LINK,
    COMPLETE_INVESTIGATION
}
