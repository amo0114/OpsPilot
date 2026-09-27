package io.github.ismoyuan.opspilot.domain.diagnosis;

import static io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType.POSSIBLE_CAUSE;
import static io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED;
import static io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType.UNDETERMINED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.evidence.Evidence;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.evidence.NewEvidence;
import io.github.ismoyuan.opspilot.domain.hypothesis.Hypothesis;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 01 §20、04 §34 的 Diagnosis 规则：结论类型、主假设要求、冻结引用必须真实且属于同一 Investigation，
 * PRIMARY/POSSIBLE 的支持证据必须在本 Diagnosis 引用集合中且关联主假设。
 */
class DiagnosisRulesTest {

    private static final long INVESTIGATION = 7;
    private static final long OTHER_INVESTIGATION = 8;
    private static final Instant T0 = Instant.parse("2026-09-27T08:00:00Z");

    private static final Hypothesis PRIMARY = hypothesis(21, INVESTIGATION);
    private static final Hypothesis OTHER = hypothesis(22, INVESTIGATION);

    @Test
    void frozenConclusionTypesAndTerminationReasons() {
        assertThat(Arrays.stream(DiagnosisConclusionType.values()).map(Enum::name))
                .containsExactly("PRIMARY_CAUSE_IDENTIFIED", "POSSIBLE_CAUSE", "UNDETERMINED");
        assertThat(Arrays.stream(TerminationReason.values()).map(Enum::name))
                .containsExactly(
                        "AGENT_COMPLETED",
                        "USER_STOPPED",
                        "CAPABILITY_BUDGET_EXHAUSTED",
                        "INVESTIGATION_TIMEOUT",
                        "AI_RUNTIME_UNAVAILABLE");
    }

    @Test
    void structureRequiresPrimaryHypothesisOnlyForPrimaryAndPossible() {
        for (DiagnosisConclusionType type : List.of(PRIMARY_CAUSE_IDENTIFIED, POSSIBLE_CAUSE)) {
            assertViolation(() -> draft(type, null, List.of()), "PRIMARY_HYPOTHESIS_REQUIRED");
        }
        assertThat(draft(UNDETERMINED, null, List.of()).primaryHypothesisId()).isNull();

        assertInvalid(() -> draft(UNDETERMINED, null, List.of(41L, 41L)), "evidenceIds", "DUPLICATE");
        assertInvalid(() -> new DiagnosisDraft(UNDETERMINED, null, " ", "impact", List.of()), "summary", "BLANK");
        assertInvalid(
                () -> new DiagnosisDraft(
                        UNDETERMINED, null, "x".repeat(DiagnosisDraft.SUMMARY_MAX + 1), "impact", List.of()),
                "summary",
                "TOO_LONG");
        assertInvalid(
                () -> new DiagnosisDraft(
                        UNDETERMINED, null, "s", "x".repeat(DiagnosisDraft.IMPACT_SUMMARY_MAX + 1), List.of()),
                "impactSummary",
                "TOO_LONG");
    }

    @Test
    void primaryAndPossibleNeedSupportingEvidenceOfThePrimaryWithinTheReferencedSet() {
        Evidence supportsPrimary = evidence(41, INVESTIGATION, PRIMARY.id(), EvidenceRelation.SUPPORTS);
        Evidence refutesPrimary = evidence(42, INVESTIGATION, PRIMARY.id(), EvidenceRelation.REFUTES);
        Evidence contextPrimary = evidence(43, INVESTIGATION, PRIMARY.id(), EvidenceRelation.CONTEXT);
        Evidence supportsOther = evidence(44, INVESTIGATION, OTHER.id(), EvidenceRelation.SUPPORTS);

        for (DiagnosisConclusionType type : List.of(PRIMARY_CAUSE_IDENTIFIED, POSSIBLE_CAUSE)) {
            assertThatCode(() -> check(type, PRIMARY, supportsPrimary, supportsOther))
                    .doesNotThrowAnyException();
            // 关联主假设但不是 SUPPORTS，或 SUPPORTS 的是其他假设：都不算支持
            assertViolation(
                    () -> check(type, PRIMARY, refutesPrimary, contextPrimary, supportsOther),
                    "SUPPORTING_EVIDENCE_REQUIRED");
            assertViolation(() -> check(type, PRIMARY), "SUPPORTING_EVIDENCE_REQUIRED");
        }
    }

    @Test
    void everyReferenceMustBeRealAndInTheSameInvestigation() {
        Evidence supportsPrimary = evidence(41, INVESTIGATION, PRIMARY.id(), EvidenceRelation.SUPPORTS);
        Evidence foreign = evidence(51, OTHER_INVESTIGATION, 99, EvidenceRelation.SUPPORTS);

        // 引用的 id 读不到（不存在）
        DiagnosisDraft missing = draft(PRIMARY_CAUSE_IDENTIFIED, PRIMARY.id(), List.of(41L, 404L));
        assertViolation(
                () -> missing.checkReferences(INVESTIGATION, PRIMARY, List.of(supportsPrimary)),
                "EVIDENCE_NOT_IN_INVESTIGATION");
        assertViolation(() -> check(UNDETERMINED, null, foreign), "EVIDENCE_NOT_IN_INVESTIGATION");

        // 主假设不存在或属于其他 Investigation
        DiagnosisDraft primaryDraft = draft(PRIMARY_CAUSE_IDENTIFIED, PRIMARY.id(), List.of(41L));
        assertViolation(
                () -> primaryDraft.checkReferences(INVESTIGATION, null, List.of(supportsPrimary)),
                "PRIMARY_HYPOTHESIS_NOT_IN_INVESTIGATION");
        assertViolation(
                () -> primaryDraft.checkReferences(
                        INVESTIGATION, hypothesis(PRIMARY.id(), OTHER_INVESTIGATION), List.of(supportsPrimary)),
                "PRIMARY_HYPOTHESIS_NOT_IN_INVESTIGATION");

        // UNDETERMINED 可以没有主假设与证据，也可以引用真实的同调查证据
        assertThatCode(() -> check(UNDETERMINED, null)).doesNotThrowAnyException();
        assertThatCode(() -> check(UNDETERMINED, null, supportsPrimary)).doesNotThrowAnyException();
    }

    private static void check(DiagnosisConclusionType type, Hypothesis primary, Evidence... referenced) {
        draft(
                        type,
                        primary == null ? null : primary.id(),
                        Arrays.stream(referenced).map(Evidence::id).toList())
                .checkReferences(INVESTIGATION, primary, List.of(referenced));
    }

    private static DiagnosisDraft draft(DiagnosisConclusionType type, Long primary, List<Long> evidenceIds) {
        return new DiagnosisDraft(type, primary, "统计消费者已停止", "统计数据延迟", evidenceIds);
    }

    private static Hypothesis hypothesis(long id, long investigationId) {
        return new Hypothesis(id, investigationId, "H" + id, null, HypothesisStatus.SUPPORTED, T0, T0, 1);
    }

    private static Evidence evidence(long id, long investigationId, long hypothesisId, EvidenceRelation relation) {
        return new Evidence(id, new NewEvidence(investigationId, 100 + id, hypothesisId, relation, "r"), T0);
    }

    private static void assertViolation(Runnable action, String reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.DIAGNOSIS_INVARIANT_VIOLATION);
            assertThat(ex.details()).containsEntry("reason", reason);
        });
    }

    private static void assertInvalid(Runnable action, String field, String reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_VALIDATION_FAILED);
            assertThat(ex.details()).containsEntry("field", field).containsEntry("reason", reason);
        });
    }
}
