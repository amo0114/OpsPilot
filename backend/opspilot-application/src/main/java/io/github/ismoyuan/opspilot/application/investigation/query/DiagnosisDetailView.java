package io.github.ismoyuan.opspilot.application.investigation.query;

import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import java.time.Instant;
import java.util.List;

/**
 * 05 §56：创建时冻结引用的具体 Evidence（来自 diagnosis_evidence_ref），不是主假设当前的全部 Evidence。
 *
 * @param primaryHypothesisId UNDETERMINED 时可为空，此时 primaryHypothesisTitle 也为空
 */
public record DiagnosisDetailView(
        int version,
        int runNo,
        DiagnosisConclusionType conclusionType,
        Long primaryHypothesisId,
        String primaryHypothesisTitle,
        String summary,
        String impactSummary,
        TerminationReason terminationReason,
        Instant createdAt,
        List<EvidenceView> evidence) {

    public DiagnosisDetailView {
        evidence = List.copyOf(evidence);
    }
}
