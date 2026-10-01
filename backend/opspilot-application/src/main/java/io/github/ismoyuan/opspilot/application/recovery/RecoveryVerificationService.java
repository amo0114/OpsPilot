package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.capability.CapabilityInvocationRepository;
import io.github.ismoyuan.opspilot.application.capability.RecoverySampleInvocation;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.dispatch.RecoveryVerificationWorker;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySnapshotV1.SnapshotCriterion;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationRepository.RecoveryVerificationRecord;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import io.github.ismoyuan.opspilot.domain.timeline.IncidentResolvedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.RecoveryVerificationEventPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RecoveryVerification Runner（08 TASK-079、06 §113～§116、04 §80、07 §68～§70）：依冻结快照的顺序逐项采样并求值，得到唯一整体结果。
 * 完全确定性，不调用 AI；判定只用 {@link RecoveryPredicateEvaluator}，不另维护优先级。
 *
 * <ol>
 *   <li>PENDING：短事务锁 Incident（须 VERIFYING），条件更新 PENDING → RUNNING 并写 RECOVERY_VERIFICATION_STARTED。PENDING 已过
 *       deadline 则不开始，直接按结果矩阵收束。
 *   <li>逐 Criterion（快照顺序、串行）：样本身份与进度只读自持久化槽位（verificationId＋criterionKey＋sampleIndex），下一个序号为已
 *       登记的最大序号＋1，失败或中断的槽位不重试；第一次样本立即准入，后续最早为上一实际样本完成时间＋intervalSeconds，在事务外
 *       等待且不越过 deadline（{@link RecoverySampler}）。
 *   <li>每个样本之后按同一求值器判断：required 明确 FALSE 即短路，后续检查不再执行并记 NOT_EXECUTED；UNKNOWN 不短路，继续后续
 *       检查以发现明确 FALSE。能力不可用记 NOT_ADMITTED，期限已到的剩余样本如实为不足。
 *   <li>终态短事务：重读 Verification 仍为本次持有，以持久化样本与当前时刻重新求值，写状态、recovery.verification.result / 1、
 *       result_summary、finished_at 与 RECOVERY_VERIFICATION_PASSED/FAILED/INCONCLUSIVE，并在同一事务内按结果迁移 Incident
 *       （PASSED → RESOLVED、FAILED → INVESTIGATING 新 run、INCONCLUSIVE → DIAGNOSED，TASK-082）。
 * </ol>
 * 等待被中断时停止并保持 RUNNING，由启动恢复或补派发按持久化槽位继续（TASK-083）。
 */
@Service
public class RecoveryVerificationService implements RecoveryVerificationWorker {

    private static final Logger log = LoggerFactory.getLogger(RecoveryVerificationService.class);

    /** 与 recovery_verification.result_summary 列长度一致。 */
    static final int SUMMARY_MAX = 1000;

    private final RecoveryVerificationRepository verifications;
    private final IncidentRepository incidents;
    private final CapabilityInvocationRepository invocations;
    private final RecoverySampler sampler;
    private final RecoverySampleReader sampleReader;
    private final SchemaCodecRegistry codecs;
    private final TimelineRepository timeline;
    private final InvestigationApplicationService investigations;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RecoveryVerificationService(
            RecoveryVerificationRepository verifications,
            IncidentRepository incidents,
            CapabilityInvocationRepository invocations,
            RecoverySampler sampler,
            RecoverySampleReader sampleReader,
            SchemaCodecRegistry codecs,
            TimelineRepository timeline,
            InvestigationApplicationService investigations,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.verifications = verifications;
        this.incidents = incidents;
        this.invocations = invocations;
        this.sampler = sampler;
        this.sampleReader = sampleReader;
        this.codecs = codecs;
        this.timeline = timeline;
        this.investigations = investigations;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void runRecoveryVerification(long verificationId) {
        Optional<RecoveryVerificationRecord> found = verifications.findById(verificationId);
        if (found.isEmpty() || found.get().status().terminal()) {
            log.debug("Verification not active, nothing to run: verificationId={}", verificationId);
            return;
        }
        RecoveryVerificationRecord verification = found.get();
        RecoveryPolicySnapshotV1 snapshot = snapshot(verification);
        if (verification.status() == RecoveryVerificationStatus.PENDING) {
            if (!now().isBefore(verification.deadlineAt())) {
                finish(verification, snapshot, Map.of());
                return;
            }
            Boolean started = transaction.execute(status -> start(verification));
            if (!Boolean.TRUE.equals(started)) {
                return;
            }
        }
        Map<String, CriterionReason> notes = new HashMap<>();
        boolean failed = false;
        for (SnapshotCriterion criterion : snapshot.criteria()) {
            String key = criterion.criterion().criterionKey();
            if (failed) {
                notes.put(key, CriterionReason.NOT_EXECUTED);
                continue;
            }
            Progress progress =
                    sampleCriterion(verification.id(), verification.deadlineAt(), snapshot, criterion, notes);
            switch (progress) {
                case FALSE_REQUIRED -> failed = true;
                case STOP -> {
                    return;
                }
                case DONE -> {}
            }
        }
        finish(verification, snapshot, notes);
    }

    /** 一个 Criterion 采样的去向。 */
    private enum Progress {
        /** 采样结束（完成、期限已到或未准入），继续下一项。 */
        DONE,
        /** required 明确 FALSE，短路后续检查。 */
        FALSE_REQUIRED,
        /** 不再由本 Worker 继续（Verification 已不在 RUNNING、槽位被他人占用或线程中断）。 */
        STOP
    }

    /** @param deadline Verification 创建时冻结的 deadline_at（不变） */
    private Progress sampleCriterion(
            long verificationId,
            Instant deadline,
            RecoveryPolicySnapshotV1 snapshot,
            SnapshotCriterion criterion,
            Map<String, CriterionReason> notes) {
        RecoveryCriterionV1 definition = criterion.criterion();
        RecoverySamplingV1 sampling = definition.sampling();
        while (true) {
            List<RecoverySampleInvocation> slots = slots(verificationId, definition.criterionKey());
            CriterionEvaluation evaluation = RecoveryPredicateEvaluator.evaluate(
                    definition, samples(criterion, slots), maxSampleAge(snapshot), deadline, clock.instant());
            if (evaluation.result() == CriterionResult.FALSE && definition.required()) {
                return Progress.FALSE_REQUIRED;
            }
            int next = slots.stream()
                            .mapToInt(RecoverySampleInvocation::sampleIndex)
                            .max()
                            .orElse(0)
                    + 1;
            if (next > sampling.sampleCount()) {
                return Progress.DONE;
            }
            Instant earliest = slots.isEmpty()
                    ? clock.instant()
                    : completedAt(slots.getLast()).plusSeconds(sampling.intervalSeconds());
            if (!earliest.isBefore(deadline)) {
                return Progress.DONE;
            }
            if (!await(earliest)) {
                return Progress.STOP;
            }
            switch (sampler.sample(verificationId, criterion, next)) {
                case RecoverySampler.Sampled ignored -> {}
                case RecoverySampler.NotAdmitted notAdmitted -> {
                    log.info(
                            "Recovery sample not admitted: verificationId={} criterion={} code={}",
                            verificationId,
                            definition.criterionKey(),
                            notAdmitted.code());
                    notes.put(definition.criterionKey(), CriterionReason.NOT_ADMITTED);
                    return Progress.DONE;
                }
                case RecoverySampler.Stopped stopped -> {
                    return RecoverySampler.Stopped.DEADLINE_REACHED.equals(stopped.reason())
                            ? Progress.DONE
                            : Progress.STOP;
                }
                case RecoverySampler.SlotTaken ignored -> {
                    return Progress.STOP;
                }
            }
        }
    }

    /** 事务外等待到最早准入时间。@return 被中断时为 false */
    private boolean await(Instant earliest) {
        Duration wait = Duration.between(clock.instant(), earliest);
        if (wait.isNegative() || wait.isZero()) {
            return true;
        }
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * PENDING → RUNNING 与 STARTED 事件。事务的第一条语句就是 Incident 行锁（所属 Incident 创建后不变，已在事务外读出）：锁之后的普通读
     * 才建立一致性视图，能看到锁等待期间已提交的事实（B28-R1）。
     *
     * @return 是否由本次开始（已由他人开始为 RUNNING 时也继续）
     */
    private boolean start(RecoveryVerificationRecord seen) {
        long verificationId = seen.id();
        Incident incident = incidents.findByIdForUpdate(seen.incidentId()).orElseThrow();
        RecoveryVerificationRecord current =
                verifications.findById(verificationId).orElseThrow();
        if (current.status() != RecoveryVerificationStatus.PENDING) {
            return current.status() == RecoveryVerificationStatus.RUNNING;
        }
        if (incident.status() != IncidentStatus.VERIFYING) {
            log.warn(
                    "Pending verification of an incident not VERIFYING, not started: verificationId={} status={}",
                    verificationId,
                    incident.status());
            return false;
        }
        Instant now = now();
        if (!verifications.markRunning(verificationId, current.lockVersion(), now)) {
            return false;
        }
        append(incident, current, TimelineEventType.RECOVERY_VERIFICATION_STARTED, "开始恢复验证", null, now);
        return true;
    }

    /**
     * 终态事务：以持久化样本重新求值并写入唯一结果；已不由本次持有（终态或版本变化）则不写。与 {@link #start} 相同，先取 Incident 行锁，
     * 之后才读取 Verification 与样本。
     */
    private void finish(
            RecoveryVerificationRecord seen, RecoveryPolicySnapshotV1 snapshot, Map<String, CriterionReason> notes) {
        long verificationId = seen.id();
        transaction.executeWithoutResult(status -> {
            Incident incident = incidents.findByIdForUpdate(seen.incidentId()).orElseThrow();
            RecoveryVerificationRecord current =
                    verifications.findById(verificationId).orElseThrow();
            if (current.status().terminal()) {
                return;
            }
            Instant now = now();
            RecoveryVerificationResultV1 result = evaluate(verificationId, snapshot, notes, current.deadlineAt(), now);
            RecoveryVerificationStatus outcome =
                    RecoveryVerificationStatus.valueOf(result.overallResult().name());
            if (!verifications.markFinished(
                    verificationId,
                    current.status(),
                    current.lockVersion(),
                    outcome,
                    summary(result),
                    codecs.encode(
                            RecoveryVerificationResultV1.SCHEMA_NAME,
                            RecoveryVerificationResultV1.SCHEMA_VERSION,
                            result),
                    now)) {
                return;
            }
            append(
                    incident,
                    current,
                    switch (result.overallResult()) {
                        case PASSED -> TimelineEventType.RECOVERY_VERIFICATION_PASSED;
                        case FAILED -> TimelineEventType.RECOVERY_VERIFICATION_FAILED;
                        case INCONCLUSIVE -> TimelineEventType.RECOVERY_VERIFICATION_INCONCLUSIVE;
                    },
                    summary(result),
                    result.overallResult().name(),
                    now);
            transitionIncident(incident, current, result.overallResult(), now);
        });
    }

    /**
     * 同一终态事务内按结果迁移 Incident（08 TASK-082、01 §30～§31）：PASSED → RESOLVED（写 resolved_at）并写 INCIDENT_RESOLVED；
     * FAILED → INVESTIGATING，经调查服务的统一新 run 入口，提交后派发，不重放 CHANGE；INCONCLUSIVE → DIAGNOSED，不自动开启新 run。
     * Incident 不在 VERIFYING（生命周期上不应出现）时只记录告警，不迁移。
     */
    private void transitionIncident(
            Incident incident, RecoveryVerificationRecord verification, RecoveryOutcome outcome, Instant now) {
        if (incident.status() != IncidentStatus.VERIFYING) {
            log.warn(
                    "Verification finished for an incident not VERIFYING, incident left unchanged: verificationId={}"
                            + " status={}",
                    verification.id(),
                    incident.status());
            return;
        }
        switch (outcome) {
            case PASSED -> {
                incidents.apply(incident.transitionFor(IncidentTrigger.VERIFICATION_PASSED, incident.version()), now);
                timeline.append(new NewTimelineEvent(
                        incident.id(),
                        TimelineEventType.INCIDENT_RESOLVED,
                        now,
                        TimelineActorType.SYSTEM,
                        null,
                        "恢复验证通过，故障已解决",
                        new IncidentResolvedPayloadV1(
                                incident.incidentKey().value(), verification.id(), verification.verificationNo()),
                        Correlation.currentId()));
            }
            case FAILED -> investigations.resumeAfterFailedVerification(incident, now);
            case INCONCLUSIVE ->
                incidents.apply(
                        incident.transitionFor(IncidentTrigger.VERIFICATION_INCONCLUSIVE, incident.version()), now);
        }
    }

    /**
     * 按快照顺序求值全部检查。求值器给出 TRUE/FALSE 时原样采用；UNKNOWN 且 Runner 记录了未执行或未准入时，以该原因如实说明。
     */
    private RecoveryVerificationResultV1 evaluate(
            long verificationId,
            RecoveryPolicySnapshotV1 snapshot,
            Map<String, CriterionReason> notes,
            Instant deadline,
            Instant now) {
        Map<String, List<RecoverySampleInvocation>> byCriterion = new HashMap<>();
        for (RecoverySampleInvocation slot : invocations.findRecoverySamples(verificationId)) {
            byCriterion
                    .computeIfAbsent(slot.criterionKey(), key -> new ArrayList<>())
                    .add(slot);
        }
        List<RecoveryVerificationResultV1.Check> checks = new ArrayList<>();
        List<RecoveryPredicateEvaluator.Check> matrix = new ArrayList<>();
        for (SnapshotCriterion criterion : snapshot.criteria()) {
            RecoveryCriterionV1 definition = criterion.criterion();
            List<RecoverySampleInvocation> slots = byCriterion.getOrDefault(definition.criterionKey(), List.of());
            List<RecoverySample> samples = samples(criterion, slots);
            CriterionEvaluation evaluation =
                    RecoveryPredicateEvaluator.evaluate(definition, samples, maxSampleAge(snapshot), deadline, now);
            CriterionReason note = notes.get(definition.criterionKey());
            if (evaluation.result() == CriterionResult.UNKNOWN && note != null) {
                evaluation = CriterionEvaluation.unknown(note);
            }
            matrix.add(new RecoveryPredicateEvaluator.Check(definition.required(), evaluation.result()));
            checks.add(new RecoveryVerificationResultV1.Check(
                    definition.criterionKey(),
                    definition.name(),
                    definition.required(),
                    evaluation.result(),
                    evaluation.reason(),
                    samples.stream()
                            .map(sample -> new RecoveryVerificationResultV1.Sample(
                                    sample.sampleIndex(), sample.invocationId(), sample.status(), sample.sampledAt()))
                            .toList()));
        }
        return RecoveryVerificationResultV1.of(RecoveryPredicateEvaluator.overall(matrix), now, checks);
    }

    private List<RecoverySampleInvocation> slots(long verificationId, String criterionKey) {
        return invocations.findRecoverySamples(verificationId).stream()
                .filter(slot -> slot.criterionKey().equals(criterionKey))
                .toList();
    }

    private List<RecoverySample> samples(SnapshotCriterion criterion, List<RecoverySampleInvocation> slots) {
        return sampleReader.samples(criterion, slots);
    }

    /** 上一实际样本的完成时间；仍在进行的遗留调用以开始时间计。 */
    private static Instant completedAt(RecoverySampleInvocation slot) {
        return slot.finishedAt() != null ? slot.finishedAt() : slot.startedAt();
    }

    private RecoveryPolicySnapshotV1 snapshot(RecoveryVerificationRecord verification) {
        return codecs.decode(
                RecoveryPolicySnapshotV1.SCHEMA_NAME,
                RecoveryPolicySnapshotV1.SCHEMA_VERSION,
                verification.policySnapshot(),
                RecoveryPolicySnapshotV1.class);
    }

    private static Duration maxSampleAge(RecoveryPolicySnapshotV1 snapshot) {
        return Duration.ofSeconds(snapshot.maxSampleAgeSeconds());
    }

    /** result_payload 的用户摘要：整体结论与起决定作用的第一项检查，不是第二套判定。 */
    static String summary(RecoveryVerificationResultV1 result) {
        long required = result.checks().stream()
                .filter(RecoveryVerificationResultV1.Check::required)
                .count();
        String text = switch (result.overallResult()) {
            case PASSED -> "恢复验证通过：" + required + " 项必需检查全部满足";
            case FAILED ->
                result.checks().stream()
                        .filter(check -> check.required() && check.result() == CriterionResult.FALSE)
                        .findFirst()
                        .map(check -> "恢复验证未通过：" + check.name() + "（" + check.criterionKey() + "）明确不满足")
                        .orElseThrow();
            case INCONCLUSIVE ->
                result.checks().stream()
                        .filter(check -> check.required() && check.result() == CriterionResult.UNKNOWN)
                        .findFirst()
                        .map(check -> "恢复验证无法确认：" + check.name() + "（" + check.criterionKey() + "）"
                                + check.reason().name())
                        .orElseThrow();
        };
        return text.codePointCount(0, text.length()) > SUMMARY_MAX
                ? text.substring(0, text.offsetByCodePoints(0, SUMMARY_MAX))
                : text;
    }

    private void append(
            Incident incident,
            RecoveryVerificationRecord verification,
            TimelineEventType type,
            String summary,
            String overallResult,
            Instant now) {
        timeline.append(new NewTimelineEvent(
                incident.id(),
                type,
                now,
                TimelineActorType.SYSTEM,
                null,
                summary,
                new RecoveryVerificationEventPayloadV1(
                        incident.incidentKey().value(),
                        verification.id(),
                        verification.verificationNo(),
                        overallResult),
                Correlation.currentId()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
