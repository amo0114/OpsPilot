package io.github.ismoyuan.opspilot.application.investigation;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.IncidentLocks;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import io.github.ismoyuan.opspilot.domain.investigation.Investigation;
import io.github.ismoyuan.opspilot.domain.investigation.InvestigationLimits;
import io.github.ismoyuan.opspilot.domain.timeline.InvestigationStartedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.InvestigationStopRequestedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 开始与继续调查（05 §24、§28，01 §9）。两者共用 {@link #resumeInvestigation}：一个短事务内按 Incident → Investigation
 * 锁序校验来源状态与版本、建立或复用唯一 Investigation 并进入新 run、条件迁移 Incident、追加时间线；提交后才派发。
 * 应用重启恢复不走这里（07 §52），VerificationFailed 回到调查在 TASK-082 接入。
 */
@Service
public class InvestigationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationApplicationService.class);

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final TimelineRepository timeline;
    private final WorkDispatcher dispatcher;
    private final InvestigationLimits limits;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public InvestigationApplicationService(
            IncidentRepository incidents,
            InvestigationRepository investigations,
            TimelineRepository timeline,
            WorkDispatcher dispatcher,
            InvestigationLimits limits,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.timeline = timeline;
        this.dispatcher = dispatcher;
        this.limits = limits;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** CREATED → INVESTIGATING，创建唯一 Investigation 并初始化 run 1。 */
    public InvestigationRunResult startInvestigation(StartInvestigationCommand command) {
        return resumeInvestigation(
                command.incidentKey(), command.expectedVersion(), IncidentTrigger.START_INVESTIGATION, command.actor());
    }

    /** DIAGNOSED → INVESTIGATING，复用唯一 Investigation 进入下一 run；等待审批时拒绝。 */
    public InvestigationRunResult continueInvestigation(ContinueInvestigationCommand command) {
        return resumeInvestigation(
                command.incidentKey(),
                command.expectedVersion(),
                IncidentTrigger.CONTINUE_INVESTIGATION,
                command.actor());
    }

    /**
     * 协作式停止当前 run（05 §27、08 TASK-018）：按 Incident → Investigation 锁序校验，首次 Stop 同事务写停止时间与身份、
     * Incident 与 Investigation 版本各加一并追加一条 INVESTIGATION_STOP_REQUESTED；状态仍为 INVESTIGATING。
     * 同一仍活跃 run 已 Stop 时返回既有接受结果，不再写事件或版本。收束由调查 Worker 完成（TASK-041/042）。
     */
    public InvestigationRunResult stopInvestigation(StopInvestigationCommand command) {
        return transaction.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Incident incident = lockIncident(command.incidentKey());
            if (incident.status() != IncidentStatus.INVESTIGATING) {
                throw new ApplicationException(
                        ErrorCode.INCIDENT_STATE_CONFLICT,
                        "Stop requires an active investigation",
                        Map.of(
                                "incidentKey", incident.incidentKey().value(),
                                "currentStatus", incident.status().name(),
                                "expectedStatuses", List.of(IncidentStatus.INVESTIGATING.name())));
            }
            Investigation investigation = investigations
                    .findByIncidentIdForUpdate(incident.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "INVESTIGATING incident without investigation: " + incident.id()));
            if (investigation.stopRequested()) {
                return runResult(incident, investigation);
            }
            if (command.expectedVersion() != incident.version()) {
                throw new ApplicationException(
                        ErrorCode.INCIDENT_VERSION_CONFLICT,
                        "Incident version changed before stop",
                        Map.of(
                                "incidentKey", incident.incidentKey().value(),
                                "currentStatus", incident.status().name(),
                                "version", incident.version()));
            }
            Investigation stopped = investigations.saveStopRequest(
                    investigation, investigation.withStopRequested(now, command.actor()));
            Incident touched = incidents.incrementVersion(incident, now);
            timeline.append(new NewTimelineEvent(
                    incident.id(),
                    TimelineEventType.INVESTIGATION_STOP_REQUESTED,
                    now,
                    TimelineActorType.USER,
                    command.actor(),
                    "请求停止调查（第 " + stopped.currentRunNo() + " 轮）",
                    new InvestigationStopRequestedPayloadV1(
                            incident.incidentKey().value(), stopped.currentRunNo()),
                    Correlation.currentId()));
            return runResult(touched, stopped);
        });
    }

    private static InvestigationRunResult runResult(Incident incident, Investigation investigation) {
        return new InvestigationRunResult(
                incident.incidentKey(),
                incident.status(),
                incident.version(),
                investigation.currentRunNo(),
                investigation.stopRequested());
    }

    private InvestigationRunResult resumeInvestigation(
            String incidentKey, long expectedVersion, IncidentTrigger trigger, String actor) {
        return transaction.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Incident incident = lockIncident(incidentKey);
            // 存在 PENDING Approval 即 AWAITING_APPROVAL（01 §3.4），先于版本校验给出专门错误（05 §28）
            if (trigger == IncidentTrigger.CONTINUE_INVESTIGATION
                    && incident.status() == IncidentStatus.AWAITING_APPROVAL) {
                throw new ApplicationException(
                        ErrorCode.PENDING_APPROVAL_EXISTS,
                        "Pending approval blocks continuing the investigation",
                        Map.of("incidentKey", incident.incidentKey().value()));
            }
            var transition = incident.transitionFor(trigger, expectedVersion);

            Investigation investigation = investigations
                    .findByIncidentIdForUpdate(incident.id())
                    .map(current -> investigations.saveNextRun(current, current.nextRun(now)))
                    .orElseGet(() -> investigations.insertFirstRun(incident.id(), limits, now));
            int previousRunNo = investigation.currentRunNo() - 1;
            Incident investigating = incidents.apply(transition, now);

            timeline.append(new NewTimelineEvent(
                    incident.id(),
                    TimelineEventType.INVESTIGATION_STARTED,
                    now,
                    TimelineActorType.USER,
                    actor,
                    (trigger == IncidentTrigger.START_INVESTIGATION ? "开始调查" : "继续调查") + "（第 "
                            + investigation.currentRunNo() + " 轮）",
                    new InvestigationStartedPayloadV1(
                            incident.incidentKey().value(),
                            trigger.name(),
                            previousRunNo,
                            investigation.currentRunNo(),
                            investigation.limits().maxCapabilityCalls(),
                            investigation.limits().maxDurationSeconds()),
                    Correlation.currentId()));

            dispatchAfterCommit(incident.id(), investigation.currentRunNo());
            return new InvestigationRunResult(
                    investigating.incidentKey(),
                    investigating.status(),
                    investigating.version(),
                    investigation.currentRunNo(),
                    investigation.stopRequested());
        });
    }

    private Incident lockIncident(String incidentKey) {
        return IncidentLocks.lockByKey(incidents, incidentKey);
    }

    /**
     * 只唤醒已提交的 run（07 §45）。唤醒失败不回滚已接受的业务事实，由启动扫描与补派发恢复（07 §51）。
     */
    private void dispatchAfterCommit(long incidentId, int runNo) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    dispatcher.dispatchInvestigation(incidentId, runNo);
                } catch (RuntimeException ex) {
                    log.warn(
                            "Investigation dispatch failed after commit: incidentId={} runNo={} exception={}",
                            incidentId,
                            runNo,
                            ex.getClass().getName());
                }
            }
        });
    }
}
