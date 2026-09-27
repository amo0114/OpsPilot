package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;

/** 随 PROPOSE_EVIDENCE_LINK 附带的同一 Hypothesis 状态更新（05 §82）；没有 reason。 */
public record HypothesisUpdate(long hypothesisId, HypothesisStatus targetStatus) {

    public HypothesisUpdate {
        ProtocolChecks.id("hypothesisId", hypothesisId);
        checkTarget(targetStatus);
    }

    /** PENDING 只是初始状态，不能作为目标（01 §13～§14）。 */
    static void checkTarget(HypothesisStatus targetStatus) {
        if (ProtocolChecks.required("targetStatus", targetStatus) == HypothesisStatus.PENDING) {
            throw ProtocolChecks.invalid("targetStatus");
        }
    }
}
