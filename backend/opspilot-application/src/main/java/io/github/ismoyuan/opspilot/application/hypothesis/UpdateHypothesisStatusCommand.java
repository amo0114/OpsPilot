package io.github.ismoyuan.opspilot.application.hypothesis;

import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import java.util.Objects;

/**
 * 单独调整待验证原因的当前判断（05 §84）。
 *
 * @param reason 可为空，最多 {@link HypothesisStatusRecorder#REASON_MAX} 字符
 */
public record UpdateHypothesisStatusCommand(
        long incidentId, long hypothesisId, HypothesisStatus targetStatus, String reason) {

    public UpdateHypothesisStatusCommand {
        Objects.requireNonNull(targetStatus, "targetStatus");
    }
}
