package io.github.ismoyuan.opspilot.domain.diagnosis;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 已冻结的诊断版本（03 §34～§37、04 §32～§36）：只插入、不修改；新的判断新增版本。当前诊断是 version_no 最大的一条。
 *
 * @param runNo 产生该 Diagnosis 的运行周期，此后不随 Continue 改变
 * @param primaryHypothesisId UNDETERMINED 时可为空
 * @param evidenceIds 创建时冻结引用的 Evidence，升序；后来新增的 Evidence 不改变它
 */
public record Diagnosis(
        long id,
        long investigationId,
        int runNo,
        int versionNo,
        DiagnosisConclusionType conclusionType,
        Long primaryHypothesisId,
        String summary,
        String impactSummary,
        TerminationReason terminationReason,
        List<Long> evidenceIds,
        Instant createdAt) {

    public Diagnosis {
        Objects.requireNonNull(conclusionType, "conclusionType");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(impactSummary, "impactSummary");
        Objects.requireNonNull(terminationReason, "terminationReason");
        Objects.requireNonNull(createdAt, "createdAt");
        evidenceIds = List.copyOf(evidenceIds);
        if (runNo < 1 || versionNo < 1) {
            throw new IllegalArgumentException("runNo and versionNo must be >= 1");
        }
    }
}
