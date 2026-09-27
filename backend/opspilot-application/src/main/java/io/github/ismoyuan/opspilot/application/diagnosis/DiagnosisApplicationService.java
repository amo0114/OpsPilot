package io.github.ismoyuan.opspilot.application.diagnosis;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.evidence.EvidenceRepository;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisRepository;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.investigation.ActiveInvestigation;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRepository;
import io.github.ismoyuan.opspilot.application.remediation.RemediationPlanSuperseder;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.diagnosis.Diagnosis;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisDraft;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.hypothesis.Hypothesis;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import io.github.ismoyuan.opspilot.domain.timeline.DiagnosisCreatedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Diagnosis 创建事务（08 TASK-026、01 §11、§19～§20）。一个短事务内按 Incident → Investigation 锁序确认调查进行中且草稿属于
 * 当前 run，以真实数据校验主假设与冻结引用，插入 Diagnosis 与引用、使旧未执行 Plan 失效、经唯一转换入口
 * INVESTIGATING → DIAGNOSED，并追加 DIAGNOSIS_CREATED；任一步失败整体回滚。
 *
 * <p>Stop 与 deadline 不在此拒绝：同轮 Stop 后仍可用在途合法草稿收束，到期收束也经此创建（01 §11）；
 * 是否允许收束以及 UNDETERMINED 的生成由调查 Guard 与确定性收束决定（TASK-039～042）。
 */
@Service
public class DiagnosisApplicationService {

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final HypothesisRepository hypotheses;
    private final EvidenceRepository evidence;
    private final DiagnosisRepository diagnoses;
    private final RemediationPlanSuperseder plans;
    private final TimelineRepository timeline;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public DiagnosisApplicationService(
            IncidentRepository incidents,
            InvestigationRepository investigations,
            HypothesisRepository hypotheses,
            EvidenceRepository evidence,
            DiagnosisRepository diagnoses,
            RemediationPlanSuperseder plans,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.hypotheses = hypotheses;
        this.evidence = evidence;
        this.diagnoses = diagnoses;
        this.plans = plans;
        this.timeline = timeline;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @throws ApplicationException 调查不在进行中（INCIDENT_STATE_CONFLICT）、草稿属于旧 run（STALE_RUN_RESULT）
     * @throws io.github.ismoyuan.opspilot.domain.error.DomainException 主假设或引用不满足规则（DIAGNOSIS_INVARIANT_VIOLATION）
     */
    public Diagnosis createDiagnosis(CreateDiagnosisCommand command) {
        return transaction.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            ActiveInvestigation active = ActiveInvestigation.lock(incidents, investigations, command.incidentId());
            int currentRunNo = active.investigation().currentRunNo();
            if (command.runNo() != currentRunNo) {
                throw new ApplicationException(
                        ErrorCode.STALE_RUN_RESULT,
                        "Diagnosis draft belongs to a finished run",
                        Map.of(
                                "incidentKey", active.incidentKey(),
                                "runNo", command.runNo(),
                                "currentRunNo", currentRunNo));
            }
            DiagnosisDraft draft = command.draft();
            Hypothesis primary = draft.primaryHypothesisId() == null
                    ? null
                    : hypotheses.findByIdForUpdate(draft.primaryHypothesisId()).orElse(null);
            draft.checkReferences(active.investigationId(), primary, evidence.findByIds(draft.evidenceIds()));

            Diagnosis created =
                    diagnoses.insert(active.investigationId(), currentRunNo, draft, command.terminationReason(), now);
            plans.supersedeUnexecutedPlans(active.incident().id(), created.id(), now);
            incidents.apply(active.incident().transitionFor(IncidentTrigger.COMPLETE_INVESTIGATION), now);
            timeline.append(new NewTimelineEvent(
                    active.incident().id(),
                    TimelineEventType.DIAGNOSIS_CREATED,
                    now,
                    command.terminationReason() == TerminationReason.AGENT_COMPLETED
                            ? TimelineActorType.AI_RUNTIME
                            : TimelineActorType.SYSTEM,
                    null,
                    "形成诊断 v" + created.versionNo() + "：" + describe(created.conclusionType()),
                    new DiagnosisCreatedPayloadV1(
                            active.incidentKey(),
                            active.investigationId(),
                            created.id(),
                            created.versionNo(),
                            created.runNo(),
                            created.conclusionType().name(),
                            created.primaryHypothesisId(),
                            created.evidenceIds(),
                            created.terminationReason().name()),
                    Correlation.currentId()));
            return created;
        });
    }

    private static String describe(DiagnosisConclusionType type) {
        return switch (type) {
            case PRIMARY_CAUSE_IDENTIFIED -> "已确定主要原因";
            case POSSIBLE_CAUSE -> "可能原因";
            case UNDETERMINED -> "暂时无法确定原因";
        };
    }
}
