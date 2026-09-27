package io.github.ismoyuan.opspilot.domain.diagnosis;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.evidence.Evidence;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.hypothesis.Hypothesis;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 待冻结的诊断草稿（05 §85）：结论、主假设、摘要与本 Diagnosis 引用的具体 Evidence。构造时校验结构；
 * {@link #checkReferences} 在创建事务内以真实数据校验归属与支持证据（01 §20、04 §34）。Java 只校验引用、归属与结构，
 * 不裁判语义强弱。文本上限与 V003 列长度一致。
 *
 * @param primaryHypothesisId PRIMARY/POSSIBLE 必填，UNDETERMINED 可为空
 * @param evidenceIds 本 Diagnosis 冻结引用的 Evidence，不可重复，可为空列表
 */
public record DiagnosisDraft(
        DiagnosisConclusionType conclusionType,
        Long primaryHypothesisId,
        String summary,
        String impactSummary,
        List<Long> evidenceIds) {

    public static final int SUMMARY_MAX = 2000;
    public static final int IMPACT_SUMMARY_MAX = 1000;

    public DiagnosisDraft {
        Objects.requireNonNull(conclusionType, "conclusionType");
        summary = requireText("summary", summary, SUMMARY_MAX);
        impactSummary = requireText("impactSummary", impactSummary, IMPACT_SUMMARY_MAX);
        evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        if (new HashSet<>(evidenceIds).size() != evidenceIds.size()) {
            throw invalid("evidenceIds", "DUPLICATE");
        }
        if (conclusionType.requiresSupportedPrimaryHypothesis() && primaryHypothesisId == null) {
            throw violation("PRIMARY_HYPOTHESIS_REQUIRED", Map.of("conclusionType", conclusionType.name()));
        }
    }

    /**
     * 以创建事务内读取的真实数据核对：每个引用的 Evidence 存在且属于该 Investigation，主假设（若有）属于该 Investigation；
     * PRIMARY/POSSIBLE 在本草稿引用集合中至少有一条关联主假设的 SUPPORTS Evidence。
     *
     * @param primaryHypothesis 按 {@link #primaryHypothesisId} 读到的 Hypothesis，不存在或无主假设时为空
     * @param referencedEvidence 按 {@link #evidenceIds} 读到的 Evidence，不存在的 id 不在其中
     * @throws DomainException DIAGNOSIS_INVARIANT_VIOLATION，details.reason 为 EVIDENCE_NOT_IN_INVESTIGATION、
     *     PRIMARY_HYPOTHESIS_NOT_IN_INVESTIGATION 或 SUPPORTING_EVIDENCE_REQUIRED
     */
    public void checkReferences(
            long investigationId, Hypothesis primaryHypothesis, Collection<Evidence> referencedEvidence) {
        Set<Long> valid = new LinkedHashSet<>();
        for (Evidence evidence : referencedEvidence) {
            if (evidence.content().investigationId() == investigationId) {
                valid.add(evidence.id());
            }
        }
        List<Long> invalid =
                evidenceIds.stream().filter(id -> !valid.contains(id)).toList();
        if (!invalid.isEmpty()) {
            throw violation("EVIDENCE_NOT_IN_INVESTIGATION", Map.of("evidenceIds", invalid));
        }
        if (primaryHypothesisId != null
                && (primaryHypothesis == null
                        || primaryHypothesis.id() != primaryHypothesisId
                        || primaryHypothesis.investigationId() != investigationId)) {
            throw violation(
                    "PRIMARY_HYPOTHESIS_NOT_IN_INVESTIGATION", Map.of("primaryHypothesisId", primaryHypothesisId));
        }
        if (conclusionType.requiresSupportedPrimaryHypothesis()
                && referencedEvidence.stream()
                        .noneMatch(e -> e.content().hypothesisId() == primaryHypothesisId
                                && e.content().relation() == EvidenceRelation.SUPPORTS)) {
            throw violation(
                    "SUPPORTING_EVIDENCE_REQUIRED",
                    Map.of("conclusionType", conclusionType.name(), "primaryHypothesisId", primaryHypothesisId));
        }
    }

    private static String requireText(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            throw invalid(field, "BLANK");
        }
        String text = value.strip();
        if (text.codePointCount(0, text.length()) > max) {
            throw invalid(field, "TOO_LONG");
        }
        return text;
    }

    /** details 只含字段名与原因，不回显输入值。 */
    private static DomainException invalid(String field, String reason) {
        return new DomainException(
                ErrorCode.REQUEST_VALIDATION_FAILED,
                "Invalid diagnosis field " + field + ": " + reason,
                Map.of("field", field, "reason", reason));
    }

    private static DomainException violation(String reason, Map<String, Object> context) {
        Map<String, Object> details = new HashMap<>(context);
        details.put("reason", reason);
        return new DomainException(
                ErrorCode.DIAGNOSIS_INVARIANT_VIOLATION, "Diagnosis invariant violated: " + reason, details);
    }
}
