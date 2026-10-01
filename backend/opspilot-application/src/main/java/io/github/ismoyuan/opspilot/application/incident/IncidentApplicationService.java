package io.github.ismoyuan.opspilot.application.incident;

import io.github.ismoyuan.opspilot.application.approval.PendingApprovalCanceller;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.system.ManagedSystemRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentActionPolicy;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentSource;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTransition;
import io.github.ismoyuan.opspilot.domain.incident.IncidentTrigger;
import io.github.ismoyuan.opspilot.domain.incident.NewIncident;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.ManagedSystem;
import io.github.ismoyuan.opspilot.domain.timeline.IncidentCancelledPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.IncidentCreatedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Incident 用例（05 §20～§21）。事务只包含数据库操作（07 §37）。 */
@Service
public class IncidentApplicationService {

    /**
     * 编号按当日最大序号 +1 分配；并发创建撞上 uk_incident_key 时整事务回滚并在新事务重新分配。
     * n 个同时创建最坏需要 n 次，单用户 Demo 下 5 次足够；超过则如实失败而不是跳号猜测。
     */
    static final int MAX_KEY_ATTEMPTS = 5;

    private final ManagedSystemRepository systems;
    private final ManagedResourceRepository resources;
    private final IncidentRepository incidents;
    private final TimelineRepository timeline;
    private final PendingApprovalCanceller pendingApprovals;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public IncidentApplicationService(
            ManagedSystemRepository systems,
            ManagedResourceRepository resources,
            IncidentRepository incidents,
            TimelineRepository timeline,
            PendingApprovalCanceller pendingApprovals,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.systems = systems;
        this.resources = resources;
        this.incidents = incidents;
        this.timeline = timeline;
        this.pendingApprovals = pendingApprovals;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Incident、受影响资源与 INCIDENT_CREATED 时间线在同一事务内一起提交或一起回滚。 */
    public CreateIncidentResult createIncident(CreateIncidentCommand command) {
        List<String> resourceKeys = requireDistinctResourceKeys(command.affectedResourceKeys());
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> createInTransaction(command, resourceKeys));
            } catch (IncidentKeyTakenException ex) {
                if (attempt >= MAX_KEY_ATTEMPTS) {
                    throw ex;
                }
            }
        }
    }

    /**
     * 取消故障处理（05 §33、08 TASK-019）：只允许状态机规定的来源；迁移与时间线同事务。等待审批时同事务经
     * {@link PendingApprovalCanceller} 取消 PENDING Approval 与未执行 Plan。取消调查中的 Incident 后，旧 run 的迟到结果
     * 由调查准入按 Incident 状态拒绝（TASK-039/041）。
     */
    public CancelIncidentResult cancelIncident(CancelIncidentCommand command) {
        String reason = optionalReason(command.reason());
        return transaction.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Incident incident = IncidentLocks.lockByKey(incidents, command.incidentKey());
            IncidentTransition transition =
                    incident.transitionFor(IncidentTrigger.CANCEL_INCIDENT, command.expectedVersion());
            if (incident.status() == IncidentStatus.AWAITING_APPROVAL) {
                pendingApprovals.cancelPendingApprovalAndPlans(incident.id(), now, command.actor());
            }
            Incident cancelled = incidents.apply(transition, now);
            timeline.append(new NewTimelineEvent(
                    incident.id(),
                    TimelineEventType.INCIDENT_CANCELLED,
                    now,
                    TimelineActorType.USER,
                    command.actor(),
                    reason == null ? "取消故障处理" : "取消故障处理：" + reason,
                    new IncidentCancelledPayloadV1(
                            incident.incidentKey().value(), incident.status().name(), reason),
                    Correlation.currentId()));
            return new CancelIncidentResult(cancelled.incidentKey(), cancelled.status(), cancelled.version());
        });
    }

    private static String optionalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String text = reason.strip();
        if (text.codePointCount(0, text.length()) > CancelIncidentCommand.REASON_MAX) {
            throw new ApplicationException(
                    ErrorCode.REQUEST_VALIDATION_FAILED,
                    "Cancel reason too long",
                    Map.of("field", "reason", "reason", "TOO_LONG"));
        }
        return text;
    }

    private CreateIncidentResult createInTransaction(CreateIncidentCommand command, List<String> resourceKeys) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ManagedSystem system = systems.findBySystemKey(command.systemKey())
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.SYSTEM_NOT_FOUND,
                        "Managed system not found",
                        Map.of("systemKey", String.valueOf(command.systemKey()))));
        if (!system.isActive()) {
            throw new ApplicationException(
                    ErrorCode.REQUEST_VALIDATION_FAILED,
                    "Managed system is not active",
                    Map.of("field", "systemKey", "reason", "SYSTEM_NOT_ACTIVE"));
        }
        List<ManagedResource> affected = resolveResources(system, resourceKeys);
        NewIncident newIncident = new NewIncident(
                system.id(),
                command.title(),
                command.description(),
                command.impactSummary(),
                command.createdSource(),
                command.createdBy(),
                command.startedAt(),
                now);

        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        IncidentKey key = IncidentKey.of(day, incidents.lastSequenceOn(day) + 1);
        Incident incident = incidents.insert(newIncident, key, now);
        incidents.addAffectedResources(
                incident.id(), affected.stream().map(ManagedResource::id).toList(), now);
        // 新插入的 Incident 行已被本事务锁定，满足时间线追加的锁序要求（04 §57）
        timeline.append(new NewTimelineEvent(
                incident.id(),
                TimelineEventType.INCIDENT_CREATED,
                now,
                incident.createdSource() == IncidentSource.MANUAL ? TimelineActorType.USER : TimelineActorType.SYSTEM,
                incident.createdBy(),
                "创建故障：" + incident.title(),
                new IncidentCreatedPayloadV1(
                        key.value(),
                        system.systemKey(),
                        incident.createdSource(),
                        affected.stream().map(ManagedResource::resourceKey).toList()),
                Correlation.currentId()));
        // 新建 Incident 尚无调查、审批或诊断（05 §20）；规则与详情相同，只由 Java 给出
        return new CreateIncidentResult(
                key,
                incident.status(),
                incident.version(),
                IncidentActionPolicy.available(incident.status(), false, false, false));
    }

    /** 受影响资源必须全部属于该系统（05 §21）；在 Java 内按键精确比较。 */
    private List<ManagedResource> resolveResources(ManagedSystem system, List<String> resourceKeys) {
        Map<String, ManagedResource> byKey = resources.findAllBySystemId(system.id()).stream()
                .collect(Collectors.toMap(ManagedResource::resourceKey, Function.identity()));
        List<String> missing = resourceKeys.stream()
                .filter(key -> !byKey.containsKey(key))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new ApplicationException(
                    ErrorCode.RESOURCE_NOT_IN_SYSTEM,
                    "Affected resources do not belong to the system",
                    Map.of("systemKey", system.systemKey(), "resourceKeys", missing));
        }
        return resourceKeys.stream().map(byKey::get).toList();
    }

    private static List<String> requireDistinctResourceKeys(List<String> keys) {
        if (keys == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<String> distinct = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                throw invalidResourceKeys("BLANK");
            }
            if (!seen.add(key)) {
                throw invalidResourceKeys("DUPLICATE");
            }
            distinct.add(key);
        }
        return distinct;
    }

    private static ApplicationException invalidResourceKeys(String reason) {
        return new ApplicationException(
                ErrorCode.REQUEST_VALIDATION_FAILED,
                "Invalid affected resource keys: " + reason,
                Map.of("field", "affectedResourceKeys", "reason", reason));
    }
}
