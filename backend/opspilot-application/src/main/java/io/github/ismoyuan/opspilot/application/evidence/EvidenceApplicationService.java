package io.github.ismoyuan.opspilot.application.evidence;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisStatusRecorder;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.investigation.ActiveInvestigation;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRepository;
import io.github.ismoyuan.opspilot.application.observation.ObservationRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.evidence.Evidence;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.evidence.NewEvidence;
import io.github.ismoyuan.opspilot.domain.hypothesis.Hypothesis;
import io.github.ismoyuan.opspilot.domain.observation.Observation;
import io.github.ismoyuan.opspilot.domain.timeline.EvidenceLinkedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 证据关系创建事务（08 TASK-024、01 §17、04 §29～§31）。一个短事务内按 Incident → Investigation → Hypothesis 加锁，核对
 * Observation 与 Hypothesis 都属于当前 Investigation（恢复 Observation 不是调查证据），拒绝重复关系，插入 Evidence、
 * 追加 EVIDENCE_LINKED，并可附带同一 Hypothesis 的状态变化；任一步失败整体回滚。
 *
 * <p>只引用已存在的 Observation，从不创建或复制 Observation，因此不能借“新观测”绕过唯一关系（04 §30）。
 * 没有公开写接口（05 §99），调用方是调查 Intent 分派（TASK-040），其 run/Stop 准入不在此处。
 */
@Service
public class EvidenceApplicationService {

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final ObservationRepository observations;
    private final EvidenceRepository evidence;
    private final HypothesisStatusRecorder hypothesisStatus;
    private final TimelineRepository timeline;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public EvidenceApplicationService(
            IncidentRepository incidents,
            InvestigationRepository investigations,
            ObservationRepository observations,
            EvidenceRepository evidence,
            HypothesisStatusRecorder hypothesisStatus,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.observations = observations;
        this.evidence = evidence;
        this.hypothesisStatus = hypothesisStatus;
        this.timeline = timeline;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @throws ApplicationException EVIDENCE_LINK_ALREADY_EXISTS（原关系不变）；Observation/Hypothesis 不属于当前
     *     Investigation 时 REQUEST_VALIDATION_FAILED（reason=NOT_IN_INVESTIGATION）；调查不在进行中时 INCIDENT_STATE_CONFLICT
     */
    public EvidenceLinkResult createEvidenceLink(LinkEvidenceCommand command) {
        return transaction.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            ActiveInvestigation active = ActiveInvestigation.lock(incidents, investigations, command.incidentId());
            Hypothesis hypothesis = hypothesisStatus.lockInInvestigation(active, command.hypothesisId());
            Observation observation = investigationObservation(active, command.observationId());
            NewEvidence link = new NewEvidence(
                    active.investigationId(), observation.id(), hypothesis.id(), command.relation(), command.reason());
            evidence.findByObservationAndHypothesis(observation.id(), hypothesis.id())
                    .ifPresent(existing -> {
                        throw new ApplicationException(
                                ErrorCode.EVIDENCE_LINK_ALREADY_EXISTS,
                                "Evidence link already exists",
                                Map.of(
                                        "evidenceId", existing.id(),
                                        "observationId", observation.id(),
                                        "hypothesisId", hypothesis.id()));
                    });

            Evidence created = evidence.insert(link, now);
            timeline.append(new NewTimelineEvent(
                    active.incident().id(),
                    TimelineEventType.EVIDENCE_LINKED,
                    now,
                    TimelineActorType.AI_RUNTIME,
                    null,
                    "观测 #" + observation.id() + " " + describe(command.relation()) + "假设「" + hypothesis.title() + "」",
                    new EvidenceLinkedPayloadV1(
                            active.incidentKey(),
                            active.investigationId(),
                            created.id(),
                            observation.id(),
                            hypothesis.id(),
                            command.relation().name()),
                    Correlation.currentId()));

            Hypothesis current = hypothesis;
            if (command.hypothesisUpdate() != null && command.hypothesisUpdate() != hypothesis.status()) {
                current = hypothesisStatus.change(
                        active, hypothesis, command.hypothesisUpdate(), null, created.id(), now);
            }
            return new EvidenceLinkResult(created, current);
        });
    }

    /** 不存在、属于其他 Investigation 或为恢复观测（investigation_id 为空）时同样拒绝（04 §31、03 §54）。 */
    private Observation investigationObservation(ActiveInvestigation active, long observationId) {
        return observations
                .findById(observationId)
                .filter(o -> Objects.equals(o.content().investigationId(), active.investigationId()))
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.REQUEST_VALIDATION_FAILED,
                        "Observation does not belong to the investigation",
                        Map.of(
                                "field",
                                "observationId",
                                "reason",
                                "NOT_IN_INVESTIGATION",
                                "observationId",
                                observationId)));
    }

    private static String describe(EvidenceRelation relation) {
        return switch (relation) {
            case SUPPORTS -> "支持";
            case REFUTES -> "反驳";
            case CONTEXT -> "作为背景关联";
        };
    }
}
