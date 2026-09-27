package io.github.ismoyuan.opspilot.application.evidence;

import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import java.util.Objects;

/**
 * 登记 AI 提议的证据关系（05 §82）。附带的状态更新只能针对同一 Hypothesis，结构上不接受其他 Hypothesis。
 *
 * @param hypothesisUpdate 可为空；与当前状态相同时不产生状态变化
 */
public record LinkEvidenceCommand(
        long incidentId,
        long observationId,
        long hypothesisId,
        EvidenceRelation relation,
        String reason,
        HypothesisStatus hypothesisUpdate) {

    public LinkEvidenceCommand {
        Objects.requireNonNull(relation, "relation");
    }
}
