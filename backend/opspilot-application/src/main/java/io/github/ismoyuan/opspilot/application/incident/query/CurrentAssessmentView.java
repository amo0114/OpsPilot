package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import java.time.Instant;
import java.util.List;

/**
 * “当前判断”与“为什么这么判断”（00 §26、05 §23）：最新版本的 Diagnosis。why 只来自该 Diagnosis 创建时冻结的 SUPPORTS Evidence
 * 所依据观测的摘要（03 §38：不实时查询主假设当前的全部 Evidence），按 Evidence id 顺序去重；UNDETERMINED 可以为空。
 *
 * @param runNo 产生该 Diagnosis 的 run；Incident 已进入新一轮调查时小于当前 runNo
 * @param terminationReason 可为空
 */
public record CurrentAssessmentView(
        int diagnosisVersion,
        int runNo,
        DiagnosisConclusionType conclusionType,
        String summary,
        List<String> why,
        TerminationReason terminationReason,
        Instant createdAt) {

    public CurrentAssessmentView {
        why = List.copyOf(why);
    }
}
