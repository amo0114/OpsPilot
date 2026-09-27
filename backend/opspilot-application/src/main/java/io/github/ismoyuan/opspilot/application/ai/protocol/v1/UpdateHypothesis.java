package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;

/**
 * UPDATE_HYPOTHESIS 主 payload（05 §84）。目标不能是 PENDING；与当前状态的合法性仍由 Java 判定。
 *
 * @param reason 可为空
 */
public record UpdateHypothesis(long hypothesisId, HypothesisStatus targetStatus, String reason) {

    public UpdateHypothesis {
        ProtocolChecks.id("hypothesisId", hypothesisId);
        HypothesisUpdate.checkTarget(targetStatus);
        ProtocolChecks.optionalText("reason", reason, 1000);
    }
}
