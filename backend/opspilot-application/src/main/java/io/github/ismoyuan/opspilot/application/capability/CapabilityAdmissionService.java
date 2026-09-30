package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityArguments;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogsSearchArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RequestCapability;
import io.github.ismoyuan.opspilot.application.canonical.CanonicalJsonWriter;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRepository;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityDefinition;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.investigation.Investigation;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.timeline.CapabilityInvokedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.CapabilityRequestRejectedPayloadV1;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 调查 OBSERVE 调用的原子准入（08 TASK-048、06 §56、§123、01 §11）。一个短事务内按 Incident → Investigation 加锁（与 Stop、
 * Step 准入同一锁序），持锁后读时间，依次判定：
 *
 * <ol>
 *   <li>调查状态：INVESTIGATING、期望 run、未 Stop、未到截止、本轮额度未用尽；
 *   <li>能力可用性（{@link CapabilityAccess}，与 AI 可见 Descriptor 同一规则）；
 *   <li>参数在 Descriptor 受控域内：metricKey 为该资源声明的指标；windowKey 按 {@link WindowResolver} 解析（INCIDENT_CONTEXT 先按
 *       Incident 开始时间解析），比较总范围超过 60 分钟拒绝（06 §42），解析结果交给 Provider；
 *   <li>Duplicate Guard（TASK-047）。
 * </ol>
 *
 * 全部通过才以规范 JSON 登记 RUNNING Invocation，并在同一事务内使本轮计数与累计计数各加一；任何拒绝都不建记录、不扣预算。
 * 同一事务内按 04 §72 追加时间线：准入通过为 CAPABILITY_INVOKED，Guard 拒绝为 CAPABILITY_REQUEST_REJECTED（B18/TASK-058 补齐）。
 * 事务内没有网络、Provider 或 sleep；COMMIT 即“调用已获准开始”，不代表远端已收到请求。
 */
@Service
public class CapabilityAdmissionService {

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final ManagedResourceRepository resources;
    private final CapabilityAccess access;
    private final DuplicateGuard duplicates;
    private final CapabilityInvocationRepository invocations;
    private final CanonicalJsonWriter canonicalJson;
    private final TimelineRepository timeline;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CapabilityAdmissionService(
            IncidentRepository incidents,
            InvestigationRepository investigations,
            ManagedResourceRepository resources,
            CapabilityAccess access,
            DuplicateGuard duplicates,
            CapabilityInvocationRepository invocations,
            CanonicalJsonWriter canonicalJson,
            TimelineRepository timeline,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.resources = resources;
        this.access = access;
        this.duplicates = duplicates;
        this.invocations = invocations;
        this.canonicalJson = canonicalJson;
        this.timeline = timeline;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public CapabilityAdmission admit(long incidentId, int expectedRunNo, RequestCapability request) {
        return transaction.execute(status -> {
            Optional<Incident> incident = incidents.findByIdForUpdate(incidentId);
            if (incident.isEmpty() || incident.get().status() != IncidentStatus.INVESTIGATING) {
                return new CapabilityAdmission.NotAdmitted(StepAdmissionRejection.NOT_INVESTIGATING);
            }
            Investigation investigation = investigations
                    .findByIncidentIdForUpdate(incidentId)
                    .orElseThrow(() ->
                            new IllegalStateException("INVESTIGATING incident without investigation: " + incidentId));
            // 与 Step 准入相同：取得两把锁之后才读时间（B10-R1）
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Optional<StepAdmissionRejection> rejection = investigation.checkCapabilityAdmission(expectedRunNo, now);
            if (rejection.isPresent()) {
                return new CapabilityAdmission.NotAdmitted(rejection.get());
            }

            CapabilityAdmission admission = evaluate(incident.get(), investigation, request, now);
            recordTimeline(incident.get(), investigation, request, admission, now);
            return admission;
        });
    }

    /** 持锁后的 Guard 判定与登记；任何拒绝都不建调用、不扣预算。 */
    private CapabilityAdmission evaluate(
            Incident incident, Investigation investigation, RequestCapability request, Instant now) {
        CapabilityAccess.Decision decision = access.evaluate(
                incident.managedSystemId(),
                resources.findById(request.resourceId()),
                request.capabilityKey().key());
        if (decision instanceof CapabilityAccess.Denied denied) {
            return new CapabilityAdmission.Rejected(denied.code(), denied.reason());
        }
        CapabilityAccess.Allowed allowed = (CapabilityAccess.Allowed) decision;
        Optional<CapabilityAdmission.Rejected> invalid = checkArguments(allowed, request.arguments());
        if (invalid.isPresent()) {
            return invalid.get();
        }
        WindowResolver.Resolution resolution = resolveWindow(request.arguments(), incident.startedAt(), now);
        if (resolution instanceof WindowResolver.Invalid window) {
            return new CapabilityAdmission.Rejected(window.code(), window.reason());
        }
        ResolvedWindow window = resolution instanceof WindowResolver.Resolved resolved ? resolved.window() : null;

        CapabilityDefinition definition = allowed.definition();
        String canonicalArguments = canonicalJson.write(request.arguments());
        if (duplicates.isDuplicate(
                investigation.id(),
                definition.key().key(),
                allowed.resource().id(),
                definition.requestSchema(),
                canonicalArguments,
                now)) {
            return new CapabilityAdmission.Rejected(ErrorCode.CAPABILITY_DUPLICATE_REQUEST, "RECENT_OR_IN_FLIGHT");
        }

        long invocationId = invocations.insertRunningInvestigationCall(new NewInvestigationInvocation(
                incident.id(),
                investigation.id(),
                investigation.currentRunNo(),
                definition.key().key(),
                allowed.resource().id(),
                definition.requestSchema(),
                canonicalArguments,
                now,
                Correlation.currentId()));
        investigations.saveCapabilityAdmission(investigation, investigation.withCapabilityCallAdmitted(), now);
        return new CapabilityAdmission.Admitted(new AdmittedInvocation(
                invocationId,
                incident.id(),
                investigation.id(),
                investigation.currentRunNo(),
                allowed.resource(),
                definition,
                allowed.provider(),
                request.arguments(),
                window,
                now,
                null));
    }

    /**
     * 04 §72 事务一：准入通过即写 CAPABILITY_INVOKED；Guard 拒绝写 CAPABILITY_REQUEST_REJECTED，作为给当前 run 的结构化反馈（06 §124，
     * 进入 AI 上下文时间线）。调查状态类拒绝（Stop、截止、额度等）由循环收束，不写本事件。仍持有 Incident 行锁（TimelineRepository 前提）。
     */
    private void recordTimeline(
            Incident incident,
            Investigation investigation,
            RequestCapability request,
            CapabilityAdmission admission,
            Instant now) {
        String capability = request.capabilityKey().key();
        switch (admission) {
            case CapabilityAdmission.Admitted admitted -> {
                AdmittedInvocation invocation = admitted.invocation();
                timeline.append(new NewTimelineEvent(
                        incident.id(),
                        TimelineEventType.CAPABILITY_INVOKED,
                        now,
                        TimelineActorType.AI_RUNTIME,
                        null,
                        "调用 " + capability + " 检查 " + invocation.resource().resourceKey(),
                        new CapabilityInvokedPayloadV1(
                                incident.incidentKey().value(),
                                investigation.id(),
                                invocation.runNo(),
                                invocation.invocationId(),
                                capability,
                                invocation.resource().resourceKey()),
                        Correlation.currentId()));
            }
            case CapabilityAdmission.Rejected rejected ->
                timeline.append(new NewTimelineEvent(
                        incident.id(),
                        TimelineEventType.CAPABILITY_REQUEST_REJECTED,
                        now,
                        TimelineActorType.SYSTEM,
                        null,
                        "请求 " + capability + "（资源 " + request.resourceId() + "）未执行："
                                + rejected.code().name() + " / " + rejected.reason(),
                        new CapabilityRequestRejectedPayloadV1(
                                incident.incidentKey().value(),
                                investigation.id(),
                                investigation.currentRunNo(),
                                capability,
                                request.resourceId(),
                                rejected.code().name(),
                                rejected.reason()),
                        Correlation.currentId()));
            case CapabilityAdmission.NotAdmitted notAdmitted -> {
                // 调查状态类拒绝不写时间线
            }
        }
    }

    /** 无 windowKey 的能力返回 null（无需窗口）。 */
    private static WindowResolver.Resolution resolveWindow(
            CapabilityArguments arguments, Instant incidentStartedAt, Instant now) {
        return switch (arguments) {
            case MetricsQueryArgumentsV1 metrics ->
                WindowResolver.resolve(metrics.windowKey(), metrics.comparePreviousWindow(), incidentStartedAt, now);
            case LogsSearchArgumentsV1 logs -> WindowResolver.resolve(logs.windowKey(), false, incidentStartedAt, now);
            default -> null;
        };
    }

    /** 协议已约束枚举、长度与范围；这里只核对依赖资源配置的受控域与跨字段规则。 */
    private static Optional<CapabilityAdmission.Rejected> checkArguments(
            CapabilityAccess.Allowed allowed, CapabilityArguments arguments) {
        if (arguments instanceof MetricsQueryArgumentsV1 metrics) {
            PrometheusResourceBindingV1 prometheus =
                    (PrometheusResourceBindingV1) allowed.provider().selector();
            if (!prometheus.metrics().containsKey(metrics.metricKey())) {
                return Optional.of(new CapabilityAdmission.Rejected(
                        ErrorCode.CAPABILITY_ARGUMENT_INVALID, "METRIC_KEY_NOT_AVAILABLE"));
            }
        }
        return Optional.empty();
    }
}
