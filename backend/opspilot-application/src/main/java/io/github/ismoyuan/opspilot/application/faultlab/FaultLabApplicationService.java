package io.github.ismoyuan.opspilot.application.faultlab;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.faultlab.FaultExperimentRepository.FaultExperimentRecord;
import io.github.ismoyuan.opspilot.application.incident.CreateIncidentCommand;
import io.github.ismoyuan.opspilot.application.incident.CreateIncidentResult;
import io.github.ismoyuan.opspilot.application.incident.IncidentApplicationService;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.system.ManagedSystemRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.faultlab.FaultExperimentStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentSource;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.ManagedSystem;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 故障实验室（05 §68～§72、09 §13～§19、08 TASK-090～092）。只对 DEMO/TEST 环境的系统开放（05 §68），不进入核心 Incident 领域。
 *
 * <ol>
 *   <li>注入：短事务内校验场景、环境与资源，锁定系统行确认没有进行中的实验，插入 INJECTING 并写入 Ground Truth；事务外调用注入器
 *       inject 与 verifyInjected（07 §37：外部动作不在事务中）；确认生效后，在同一事务中创建 CREATED Incident（started_at = 故障生效时间、
 *       detected_at = 确认时间，须满足 started_at ≤ detected_at ≤ 此刻）并标 ACTIVE。未确认生效或 Incident 未创建，实验为 FAILED，不留下 Incident（05 §71）。
 *   <li>Reset：只恢复实验环境（05 §72），不改变 Incident；ACTIVE 或 FAILED 可 Reset，RESETTING 期间外部动作在事务外。
 * </ol>
 * Ground Truth 在插入时写入，确认生效时只补充注入器报告的事实（被停止的容器 09 §63、S1 Gate 实测 09 §33）；本服务的返回值、Incident 的标题与影响、时间线都
 * 不含答案（09 §21）。
 */
@Service
public class FaultLabApplicationService {

    private static final Logger log = LoggerFactory.getLogger(FaultLabApplicationService.class);

    /** 允许故障实验的系统环境（按字节比较，05 §68）。 */
    static final Set<String> ALLOWED_ENVIRONMENTS = Set.of("DEMO", "TEST");

    /** 与 fault_experiment.error_message 列长度一致。 */
    static final int ERROR_MESSAGE_MAX = 1000;

    /** 注入器不控制该系统或目标（B34-R1 P1）。 */
    static final String NOT_CONTROLLED = "INJECTOR_NOT_BOUND_TO_SYSTEM";

    static final String INTERRUPTED_MESSAGE = "Java process exited before the fault lab action finished";

    static final String INVALID_TIMES_MESSAGE =
            "Injector reported a fault start after its confirmation or in the future";

    private final FaultScenarioCatalog catalog;
    private final Map<String, FaultInjector> injectors;
    private final FaultExperimentRepository experiments;
    private final ManagedSystemRepository systems;
    private final ManagedResourceRepository resources;
    private final IncidentApplicationService incidents;
    private final SchemaCodecRegistry codecs;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public FaultLabApplicationService(
            FaultScenarioCatalog catalog,
            List<FaultInjector> injectors,
            FaultExperimentRepository experiments,
            ManagedSystemRepository systems,
            ManagedResourceRepository resources,
            IncidentApplicationService incidents,
            SchemaCodecRegistry codecs,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.catalog = catalog;
        this.injectors = injectors.stream().collect(Collectors.toMap(FaultInjector::scenarioKey, Function.identity()));
        this.experiments = experiments;
        this.systems = systems;
        this.resources = resources;
        this.incidents = incidents;
        this.codecs = codecs;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 05 §69：场景定义（调用方不得输出其 Ground Truth）。 */
    public List<FaultScenario> listScenarios() {
        return catalog.all();
    }

    /**
     * @throws ApplicationException FAULT_SCENARIO_NOT_FOUND、SYSTEM_NOT_FOUND、FAULT_SCENARIO_NOT_ALLOWED、FAULT_EXPERIMENT_STATE_CONFLICT、
     *     FAULT_INJECTION_FAILED（注入器不可用时不创建实验；注入未确认、时间不合法或 Incident 未创建时实验为 FAILED）
     */
    public InjectFaultResult inject(InjectFaultCommand command) {
        FaultScenario scenario = catalog.find(command.scenarioKey())
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.FAULT_SCENARIO_NOT_FOUND,
                        "Fault scenario not found",
                        Map.of("scenarioKey", String.valueOf(command.scenarioKey()))));
        FaultInjector injector = injectors.get(scenario.scenarioKey());
        FaultTarget target = transaction.execute(status -> prepare(scenario, command.systemKey(), injector));
        long experimentId = target.experimentId();

        FaultInjection injection;
        FaultConfirmation confirmation;
        try {
            injection = injector.inject(target);
            confirmation = injector.verifyInjected(target);
        } catch (RuntimeException ex) {
            fail(experimentId, FaultExperimentStatus.INJECTING, ex);
            throw injectionFailed(experimentId, "INJECTION_NOT_CONFIRMED");
        }
        Instant startedAt = injection == null ? null : injection.startedAt();
        Instant detectedAt = confirmation == null ? null : confirmation.detectedAt();
        // 09 §19：started_at（故障生效）≤ detected_at（首次确认）≤ 此刻；不借用人工创建 Incident 的 5 分钟容差（B33-R1 P2）
        if (startedAt == null
                || detectedAt == null
                || startedAt.isAfter(detectedAt)
                || detectedAt.isAfter(clock.instant())) {
            fail(experimentId, FaultExperimentStatus.INJECTING, new FaultInjectionException(INVALID_TIMES_MESSAGE));
            throw injectionFailed(experimentId, "INJECTION_TIMES_INVALID");
        }
        try {
            String groundTruth = completedGroundTruth(scenario, injection, confirmation);
            CreateIncidentResult incident = incidents.createIncident(
                    new CreateIncidentCommand(
                            target.systemKey(),
                            scenario.incidentTitle(),
                            null,
                            scenario.incidentImpactSummary(),
                            startedAt,
                            scenario.affectedResourceKeys(),
                            IncidentSource.FAULT_LAB,
                            command.actor()),
                    detectedAt,
                    created -> {
                        if (!experiments.markActive(experimentId, created.id(), startedAt, groundTruth, now())) {
                            throw new IllegalStateException("experiment is no longer INJECTING: " + experimentId);
                        }
                    });
            return new InjectFaultResult(experimentId, FaultExperimentStatus.ACTIVE, incident);
        } catch (RuntimeException ex) {
            // Incident 与 ACTIVE 一起回滚；故障可能仍在生效，由 Reset 恢复环境
            fail(experimentId, FaultExperimentStatus.INJECTING, ex);
            throw injectionFailed(experimentId, "INCIDENT_NOT_CREATED");
        }
    }

    /**
     * 注入器报告的事实补入 Ground Truth：被停止的容器与停止时间（09 §63）、S1 Gate 实测（09 §33）；都没有时为空，保持插入时的内容。
     */
    private String completedGroundTruth(
            FaultScenario scenario, FaultInjection injection, FaultConfirmation confirmation) {
        FaultGroundTruthV1 groundTruth = scenario.groundTruth();
        boolean completed = false;
        if (injection.stoppedContainerId() != null) {
            groundTruth = groundTruth.withStoppedConsumer(injection.stoppedContainerId(), injection.startedAt());
            completed = true;
        }
        if (confirmation.redisLatencyGate() != null) {
            groundTruth = groundTruth.withRedisLatencyGate(confirmation.redisLatencyGate());
            completed = true;
        }
        if (confirmation.mysqlSlowQueryGate() != null) {
            groundTruth = groundTruth.withMysqlSlowQueryGate(confirmation.mysqlSlowQueryGate());
            completed = true;
        }
        return completed
                ? codecs.encode(FaultGroundTruthV1.SCHEMA_NAME, FaultGroundTruthV1.SCHEMA_VERSION, groundTruth)
                : null;
    }

    /**
     * @throws ApplicationException RESOURCE_NOT_FOUND、FAULT_SCENARIO_NOT_ALLOWED、FAULT_EXPERIMENT_STATE_CONFLICT、FAULT_RESET_FAILED
     */
    public ResetFaultResult reset(long experimentId) {
        Resetting resetting = transaction.execute(status -> startReset(experimentId));
        try {
            resetting.injector().reset(resetting.target());
        } catch (RuntimeException ex) {
            fail(experimentId, FaultExperimentStatus.RESETTING, ex);
            throw new ApplicationException(
                    ErrorCode.FAULT_RESET_FAILED,
                    "Fault lab environment was not reset",
                    Map.of("experimentId", experimentId, "status", FaultExperimentStatus.FAILED.name()));
        }
        transaction.executeWithoutResult(status -> {
            lockSystemOf(experimentId);
            if (!experiments.markReset(experimentId, now())) {
                throw new IllegalStateException("experiment is no longer RESETTING: " + experimentId);
            }
        });
        return new ResetFaultResult(experimentId, FaultExperimentStatus.RESET);
    }

    /** 启动路径（{@link FaultExperimentInterruptionRecorder}）：旧进程停在注入或重置中的实验结果未知，标 FAILED 以便 Reset。 */
    int recordInterrupted(Instant startedBefore) {
        Integer marked =
                transaction.execute(status -> experiments.markInterrupted(startedBefore, INTERRUPTED_MESSAGE, now()));
        return marked == null ? 0 : marked;
    }

    /**
     * 校验顺序：系统 → 环境（05 §68，先于注入器可用性，B33-R1 P2）→ 资源 → 注入器可用且控制该系统与目标（B34-R1 P1）→ 系统行锁与进行中
     * 实验 → 插入。
     */
    private FaultTarget prepare(FaultScenario scenario, String systemKey, FaultInjector injector) {
        ManagedSystem system = systems.findBySystemKey(systemKey)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.SYSTEM_NOT_FOUND,
                        "Managed system not found",
                        Map.of("systemKey", String.valueOf(systemKey))));
        requireAllowed(system.environment(), system.systemKey());
        if (!system.isActive()) {
            throw notAllowed(scenario, system.systemKey(), "SYSTEM_NOT_ACTIVE");
        }
        ManagedResource target = activeResource(system, scenario.targetResourceKey())
                .orElseThrow(() -> notAllowed(scenario, system.systemKey(), "TARGET_RESOURCE_NOT_AVAILABLE"));
        for (String affected : scenario.affectedResourceKeys()) {
            if (activeResource(system, affected).isEmpty()) {
                throw notAllowed(scenario, system.systemKey(), "AFFECTED_RESOURCE_NOT_AVAILABLE");
            }
        }
        if (injector == null) {
            throw new ApplicationException(
                    ErrorCode.FAULT_INJECTION_FAILED,
                    "No injector for the scenario",
                    Map.of("scenarioKey", scenario.scenarioKey(), "reason", "INJECTOR_NOT_AVAILABLE"));
        }
        if (!injector.controls(system.systemKey(), target.resourceKey())) {
            throw notAllowed(scenario, system.systemKey(), NOT_CONTROLLED);
        }
        experiments.lockSystem(system.id());
        if (experiments.existsInProgress(system.id(), null)) {
            throw new ApplicationException(
                    ErrorCode.FAULT_EXPERIMENT_STATE_CONFLICT,
                    "Another fault experiment is in progress on the system",
                    Map.of("systemKey", system.systemKey(), "reason", "EXPERIMENT_IN_PROGRESS"));
        }
        long experimentId = experiments.insertInjecting(
                scenario.scenarioKey(),
                system.id(),
                target.id(),
                codecs.encode(
                        FaultGroundTruthV1.SCHEMA_NAME, FaultGroundTruthV1.SCHEMA_VERSION, scenario.groundTruth()),
                now());
        return new FaultTarget(
                experimentId, scenario.scenarioKey(), system.systemKey(), target.id(), target.resourceKey());
    }

    /** 锁序与注入相同：系统 → 实验。 */
    private Resetting startReset(long experimentId) {
        long systemId = experiments.findSystemId(experimentId).orElseThrow(() -> experimentNotFound(experimentId));
        experiments.lockSystem(systemId);
        FaultExperimentRecord experiment =
                experiments.findForUpdate(experimentId).orElseThrow(() -> experimentNotFound(experimentId));
        requireAllowed(experiment.systemEnvironment(), experiment.systemKey());
        if (!experiment.status().resettable()) {
            throw new ApplicationException(
                    ErrorCode.FAULT_EXPERIMENT_STATE_CONFLICT,
                    "Fault experiment cannot be reset in its current status",
                    Map.of(
                            "experimentId", experimentId,
                            "currentStatus", experiment.status().name(),
                            "expectedStatuses",
                                    List.of(FaultExperimentStatus.ACTIVE.name(), FaultExperimentStatus.FAILED.name())));
        }
        if (experiments.existsInProgress(systemId, experimentId)) {
            throw new ApplicationException(
                    ErrorCode.FAULT_EXPERIMENT_STATE_CONFLICT,
                    "Another fault experiment is in progress on the system",
                    Map.of("experimentId", experimentId, "reason", "OTHER_EXPERIMENT_IN_PROGRESS"));
        }
        FaultInjector injector = injectors.get(experiment.scenarioKey());
        if (injector == null) {
            throw new ApplicationException(
                    ErrorCode.FAULT_RESET_FAILED,
                    "No injector for the scenario",
                    Map.of("experimentId", experimentId, "reason", "INJECTOR_NOT_AVAILABLE"));
        }
        if (!injector.controls(experiment.systemKey(), experiment.targetResourceKey())) {
            throw new ApplicationException(
                    ErrorCode.FAULT_SCENARIO_NOT_ALLOWED,
                    "Fault scenario does not apply to the system",
                    Map.of(
                            "experimentId",
                            experimentId,
                            "systemKey",
                            experiment.systemKey(),
                            "reason",
                            NOT_CONTROLLED));
        }
        if (!experiments.markResetting(experimentId, experiment.status(), now())) {
            throw new IllegalStateException("experiment changed under its row lock: " + experimentId);
        }
        return new Resetting(
                injector,
                new FaultTarget(
                        experimentId,
                        experiment.scenarioKey(),
                        experiment.systemKey(),
                        experiment.targetResourceId(),
                        experiment.targetResourceKey()));
    }

    private record Resetting(FaultInjector injector, FaultTarget target) {}

    private Optional<ManagedResource> activeResource(ManagedSystem system, String resourceKey) {
        return resources.findBySystemIdAndResourceKey(system.id(), resourceKey).filter(ManagedResource::isActive);
    }

    private static void requireAllowed(String environment, String systemKey) {
        if (!ALLOWED_ENVIRONMENTS.contains(environment)) {
            throw new ApplicationException(
                    ErrorCode.FAULT_SCENARIO_NOT_ALLOWED,
                    "Fault lab is limited to DEMO and TEST systems",
                    Map.of("systemKey", systemKey, "reason", "ENVIRONMENT_NOT_ALLOWED"));
        }
    }

    private static ApplicationException notAllowed(FaultScenario scenario, String systemKey, String reason) {
        return new ApplicationException(
                ErrorCode.FAULT_SCENARIO_NOT_ALLOWED,
                "Fault scenario does not apply to the system",
                Map.of("scenarioKey", scenario.scenarioKey(), "systemKey", systemKey, "reason", reason));
    }

    private static ApplicationException injectionFailed(long experimentId, String reason) {
        return new ApplicationException(
                ErrorCode.FAULT_INJECTION_FAILED,
                "Fault injection was not confirmed",
                Map.of(
                        "experimentId", experimentId,
                        "status", FaultExperimentStatus.FAILED.name(),
                        "reason", reason));
    }

    private static ApplicationException experimentNotFound(long experimentId) {
        return new ApplicationException(
                ErrorCode.RESOURCE_NOT_FOUND, "Fault experiment not found", Map.of("experimentId", experimentId));
    }

    /** 记 FAILED；错误信息只取注入器给出的脱敏说明，其他异常只记类型（07 §99）。 */
    private void fail(long experimentId, FaultExperimentStatus from, RuntimeException cause) {
        String message = cause instanceof FaultInjectionException injection
                        && injection.getMessage() != null
                        && !injection.getMessage().isBlank()
                ? injection.getMessage().strip()
                : "Fault lab action failed: " + cause.getClass().getSimpleName();
        String bounded = message.codePointCount(0, message.length()) > ERROR_MESSAGE_MAX
                ? message.substring(0, message.offsetByCodePoints(0, ERROR_MESSAGE_MAX))
                : message;
        Boolean marked = transaction.execute(status -> {
            lockSystemOf(experimentId);
            return experiments.markFailed(experimentId, from, bounded, now());
        });
        log.warn(
                "Fault lab experiment failed: experimentId={} from={} recorded={} exception={}",
                experimentId,
                from,
                marked,
                cause.getClass().getSimpleName());
    }

    /**
     * 在系统 → 实验锁序下更新实验状态（B34 修复 TASK-092 死锁）：状态变化会改写 (managed_system_id, status) 索引，外键检查随之对
     * managed_system 行加 S 锁；若不先取系统行锁，就会与已持有系统行锁、正在当前读进行中实验的注入/Reset 形成环。markActive 所在的创建
     * Incident 事务在插入 Incident 时已先对系统行取得 S 锁，不在此列。
     */
    private void lockSystemOf(long experimentId) {
        experiments.lockSystem(experiments
                .findSystemId(experimentId)
                .orElseThrow(() -> new IllegalStateException("fault experiment disappeared: " + experimentId)));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
