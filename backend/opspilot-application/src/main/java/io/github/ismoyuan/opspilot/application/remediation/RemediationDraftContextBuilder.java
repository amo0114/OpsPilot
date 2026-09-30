package io.github.ismoyuan.opspilot.application.remediation;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftRequest;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.IncidentLocks;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 构造 RemediationDraftRequest（08 TASK-063、05 §29～§30 第 1 步、§87）。在只读事务中读取一致快照：Incident 必须 DIAGNOSED 且版本与
 * 调用方期望一致；最新 Diagnosis 必须是 PRIMARY_CAUSE_IDENTIFIED 或 POSSIBLE_CAUSE；Evidence 只取该 Diagnosis 冻结的，摘要为关系与
 * 所依据观测的摘要；allowedActions 由 {@link RemediationActions} 在与本次诊断相关的资源上计算。UNDETERMINED 或没有可用动作时返回
 * DIAGNOSIS_NOT_ACTIONABLE，不调用 AI。请求不含 RecoveryPolicy、容器身份或凭证（05 §88）。本类不调用 AI、不写任何数据。
 */
@Service
public class RemediationDraftContextBuilder {

    /** 与协议 EvidenceSummary.summary 上限一致。 */
    static final int EVIDENCE_SUMMARY_MAX = 1000;

    private final IncidentRepository incidents;
    private final RemediationContextQuery query;
    private final RemediationActions actions;

    public RemediationDraftContextBuilder(
            IncidentRepository incidents, RemediationContextQuery query, RemediationActions actions) {
        this.incidents = incidents;
        this.query = query;
        this.actions = actions;
    }

    /**
     * @throws ApplicationException INCIDENT_NOT_FOUND、INCIDENT_STATE_CONFLICT、INCIDENT_VERSION_CONFLICT、DIAGNOSIS_NOT_FOUND 或
     *     DIAGNOSIS_NOT_ACTIONABLE（details.reason 为 UNDETERMINED 或 NO_APPLICABLE_ACTION）
     */
    @Transactional(readOnly = true)
    public RemediationDraftContext build(String incidentKey, long expectedVersion) {
        Incident incident = find(incidentKey);
        if (incident.status() != IncidentStatus.DIAGNOSED) {
            throw new ApplicationException(
                    ErrorCode.INCIDENT_STATE_CONFLICT,
                    "Remediation requires a diagnosed incident",
                    Map.of(
                            "incidentKey", incident.incidentKey().value(),
                            "currentStatus", incident.status().name(),
                            "expectedStatuses", List.of(IncidentStatus.DIAGNOSED.name())));
        }
        if (incident.version() != expectedVersion) {
            throw new ApplicationException(
                    ErrorCode.INCIDENT_VERSION_CONFLICT,
                    "Incident version changed before remediation",
                    Map.of(
                            "incidentKey", incident.incidentKey().value(),
                            "currentStatus", incident.status().name(),
                            "version", incident.version()));
        }
        RemediationContextQuery.LatestDiagnosis diagnosis = query.findLatestDiagnosis(incident.id())
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.DIAGNOSIS_NOT_FOUND,
                        "Diagnosed incident without a diagnosis",
                        Map.of("incidentKey", incident.incidentKey().value())));
        if (diagnosis.conclusionType() == DiagnosisConclusionType.UNDETERMINED) {
            throw notActionable(incident, diagnosis, "UNDETERMINED");
        }
        List<RemediationContextQuery.FrozenEvidence> evidence = query.findFrozenEvidence(diagnosis.id());
        Set<Long> candidates = new LinkedHashSet<>();
        evidence.forEach(e -> candidates.add(e.resourceId()));
        candidates.addAll(query.findAffectedResourceIds(incident.id()));
        List<AllowedRemediationAction> allowed = actions.allowedActions(incident.managedSystemId(), candidates);
        if (allowed.isEmpty()) {
            throw notActionable(incident, diagnosis, "NO_APPLICABLE_ACTION");
        }
        RemediationDraftRequest request = new RemediationDraftRequest(
                1,
                "rem-" + UUID.randomUUID(),
                new RemediationDraftRequest.Incident(incident.incidentKey().value(), incident.impactSummary()),
                new RemediationDraftRequest.Diagnosis(
                        diagnosis.versionNo(),
                        diagnosis.conclusionType(),
                        diagnosis.summary(),
                        evidence.stream()
                                .map(e -> new RemediationDraftRequest.EvidenceSummary(e.id(), summary(e)))
                                .toList()),
                allowed.stream()
                        .map(action -> new RemediationDraftRequest.AllowedAction(
                                action.definition().key().key(),
                                action.resource().id(),
                                action.resource().resourceKey(),
                                action.resource().name()))
                        .toList());
        return new RemediationDraftContext(
                incident.id(), incident.managedSystemId(), incident.version(), diagnosis.id(), request, allowed);
    }

    private Incident find(String incidentKey) {
        IncidentKey key;
        try {
            key = new IncidentKey(incidentKey);
        } catch (RuntimeException ex) {
            throw IncidentLocks.notFound(incidentKey);
        }
        return incidents.findByKey(key).orElseThrow(() -> IncidentLocks.notFound(incidentKey));
    }

    /** 关系标签＋所依据观测的摘要，按码点截到协议上限；只来自已持久化的事实，不含 AI 当时写的理由。 */
    static String summary(RemediationContextQuery.FrozenEvidence evidence) {
        String label = switch (evidence.relation()) {
            case SUPPORTS -> "支持";
            case REFUTES -> "反驳";
            case CONTEXT -> "背景";
        };
        String text = label + "：" + evidence.observationSummary();
        return text.codePointCount(0, text.length()) <= EVIDENCE_SUMMARY_MAX
                ? text
                : text.substring(0, text.offsetByCodePoints(0, EVIDENCE_SUMMARY_MAX));
    }

    private static ApplicationException notActionable(
            Incident incident, RemediationContextQuery.LatestDiagnosis diagnosis, String reason) {
        return new ApplicationException(
                ErrorCode.DIAGNOSIS_NOT_ACTIONABLE,
                "Diagnosis cannot be used for remediation",
                Map.of(
                        "incidentKey", incident.incidentKey().value(),
                        "diagnosisVersion", diagnosis.versionNo(),
                        "reason", reason));
    }
}
