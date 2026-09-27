package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import java.util.List;

/**
 * 线上 Diagnosis 草稿（05 §85）：只校验结构，PRIMARY/POSSIBLE 必须给主假设；引用是否真实、是否支持主假设由
 * 领域 DiagnosisDraft 在创建事务内判定（TASK-025/026）。
 *
 * @param primaryHypothesisId UNDETERMINED 时可为空
 * @param evidenceIds 不重复，至多 {@link #MAX_EVIDENCE}
 */
public record DiagnosisDraftV1(
        DiagnosisConclusionType conclusionType,
        Long primaryHypothesisId,
        String summary,
        String impactSummary,
        List<Long> evidenceIds) {

    /** 单个 Diagnosis 可冻结的引用上限（本批协议取值）。 */
    public static final int MAX_EVIDENCE = 50;

    public DiagnosisDraftV1 {
        ProtocolChecks.required("conclusionType", conclusionType);
        if (primaryHypothesisId != null) {
            ProtocolChecks.id("primaryHypothesisId", primaryHypothesisId);
        } else if (conclusionType.requiresSupportedPrimaryHypothesis()) {
            throw ProtocolChecks.invalid("primaryHypothesisId");
        }
        ProtocolChecks.text("summary", summary, 2000);
        ProtocolChecks.text("impactSummary", impactSummary, 1000);
        evidenceIds = ProtocolChecks.uniqueList("evidenceIds", evidenceIds, 0, MAX_EVIDENCE);
        evidenceIds.forEach(id -> ProtocolChecks.id("evidenceIds", id));
    }
}
