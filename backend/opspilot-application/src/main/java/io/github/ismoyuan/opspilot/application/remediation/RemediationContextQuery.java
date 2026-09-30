package io.github.ismoyuan.opspilot.application.remediation;

import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import java.util.List;
import java.util.Optional;

/** 处理建议上下文的只读查询（08 TASK-063）：当前 Diagnosis、它冻结的 Evidence 与受影响资源。 */
public interface RemediationContextQuery {

    /** 该 Incident 最新版本的 Diagnosis（01 §19：页面与处理建议都以最新版本为准）。 */
    Optional<LatestDiagnosis> findLatestDiagnosis(long incidentId);

    /** Diagnosis 创建时冻结的 Evidence（按 id），不是 Hypothesis 当前的全部 Evidence（05 §56）。 */
    List<FrozenEvidence> findFrozenEvidence(long diagnosisId);

    /** 创建 Incident 时登记的受影响资源 id（升序）。 */
    List<Long> findAffectedResourceIds(long incidentId);

    record LatestDiagnosis(long id, int versionNo, DiagnosisConclusionType conclusionType, String summary) {}

    /** @param resourceId 证据所依据 Observation 的资源 */
    record FrozenEvidence(long id, EvidenceRelation relation, String observationSummary, long resourceId) {}
}
