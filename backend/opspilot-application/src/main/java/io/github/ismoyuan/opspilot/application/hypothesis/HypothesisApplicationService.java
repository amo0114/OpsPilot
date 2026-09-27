package io.github.ismoyuan.opspilot.application.hypothesis;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.investigation.ActiveInvestigation;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.hypothesis.Hypothesis;
import io.github.ismoyuan.opspilot.domain.hypothesis.NewHypothesis;
import io.github.ismoyuan.opspilot.domain.timeline.HypothesisCreatedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 待验证原因的提出与状态调整（08 TASK-023、01 §13～§14）。每个用例一个短事务：按 Incident → Investigation 锁序确认调查进行中，
 * 写 Hypothesis 与对应时间线后一起提交。没有公开写接口（05 §51），调用方是调查 Intent 分派（TASK-040），
 * 其 run/Stop 准入不在此处。
 */
@Service
public class HypothesisApplicationService {

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final HypothesisRepository hypotheses;
    private final HypothesisStatusRecorder statusRecorder;
    private final TimelineRepository timeline;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public HypothesisApplicationService(
            IncidentRepository incidents,
            InvestigationRepository investigations,
            HypothesisRepository hypotheses,
            HypothesisStatusRecorder statusRecorder,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.hypotheses = hypotheses;
        this.statusRecorder = statusRecorder;
        this.timeline = timeline;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 以 PENDING 创建并追加 HYPOTHESIS_CREATED。 */
    public Hypothesis proposeHypothesis(ProposeHypothesisCommand command) {
        return transaction.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            ActiveInvestigation active = ActiveInvestigation.lock(incidents, investigations, command.incidentId());
            Hypothesis created = hypotheses.insert(
                    new NewHypothesis(active.investigationId(), command.title(), command.description()), now);
            timeline.append(new NewTimelineEvent(
                    active.incident().id(),
                    TimelineEventType.HYPOTHESIS_CREATED,
                    now,
                    TimelineActorType.AI_RUNTIME,
                    null,
                    "提出待验证原因：" + created.title(),
                    new HypothesisCreatedPayloadV1(
                            active.incidentKey(), active.investigationId(), created.id(), created.title()),
                    Correlation.currentId()));
            return created;
        });
    }

    /** 状态变化须合法（{@link io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus#canChangeTo}），与时间线同事务。 */
    public Hypothesis updateHypothesisStatus(UpdateHypothesisStatusCommand command) {
        return transaction.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            ActiveInvestigation active = ActiveInvestigation.lock(incidents, investigations, command.incidentId());
            Hypothesis locked = statusRecorder.lockInInvestigation(active, command.hypothesisId());
            return statusRecorder.change(active, locked, command.targetStatus(), command.reason(), null, now);
        });
    }
}
