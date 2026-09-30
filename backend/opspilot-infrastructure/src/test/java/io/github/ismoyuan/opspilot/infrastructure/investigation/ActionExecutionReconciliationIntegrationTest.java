package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.AiDecisionPort;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftResponse;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ServiceRestartParametersV1;
import io.github.ismoyuan.opspilot.application.approval.ApprovalApplicationService;
import io.github.ismoyuan.opspilot.application.approval.ApprovalDecisionCommand;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRecoveryService;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRepository;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionService;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutionContextV1;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartReconciliationResultV1;
import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector;
import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector.Inspected;
import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector.NotInspected;
import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector.RuntimeInspection;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyActivationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySelector;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyValidator;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationCreator;
import io.github.ismoyuan.opspilot.application.remediation.RemediationActions;
import io.github.ismoyuan.opspilot.application.remediation.RemediationApplicationService;
import io.github.ismoyuan.opspilot.application.remediation.RemediationDraftContextBuilder;
import io.github.ismoyuan.opspilot.application.remediation.RemediationProposalValidator;
import io.github.ismoyuan.opspilot.application.remediation.RequestRemediationCommand;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 08 TASK-072：真实 MySQL 上的有界只读核对。经真实 request-remediation 与批准得到 Execution；restart 执行器与只读检查器为正式 Bean 的
 * Spy（按用例替换结果，一个用例对真实容器）。验证先登记并提交再 inspect、只接受同一容器在执行开始之后启动、耗尽/到期收束为
 * EXECUTION_RESULT_UNCERTAIN、登记后崩溃只消耗该次且不刷新上限与截止时间，以及核对分支从不重发 restart。尝试间隔配置为 1 秒以缩短用例。
 */
@SpringBootTest(properties = "opspilot.execution.reconciliation-interval-seconds=1")
@Testcontainers
@Import({
    RemediationApplicationService.class,
    RemediationDraftContextBuilder.class,
    RemediationActions.class,
    RemediationProposalValidator.class,
    ApprovalApplicationService.class,
    RecoveryPolicySelector.class,
    RecoveryPolicyValidator.class,
    RecoveryPolicyActivationService.class,
    ActionExecutionService.class,
    ActionExecutionRecoveryService.class,
    RecoveryVerificationCreator.class,
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    ClockConfiguration.class
})
class ActionExecutionReconciliationIntegrationTest {

    static final String CONTAINER_ID = "c".repeat(64);
    static final String OTHER_ID = "d".repeat(64);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    RemediationApplicationService remediations;

    @Autowired
    ApprovalApplicationService approvals;

    @Autowired
    RecoveryPolicyActivationService recoveryPolicies;

    @Autowired
    ActionExecutionService worker;

    @Autowired
    SchemaCodecRegistry codecs;

    @Autowired
    ActionExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AiDecisionPort ai;

    @MockitoBean
    WorkDispatcher dispatcher;

    @MockitoSpyBean
    ServiceRestartExecutor executor;

    @MockitoSpyBean
    ServiceRuntimeInspector inspector;

    RemediationFixture seeded;

    /** 每次 inspect 时数据库中已提交的核对状态与是否处在事务中。 */
    final List<Map<String, Object>> committedAtInspect = new ArrayList<>();

    @BeforeEach
    void seed() {
        seeded = RemediationFixture.seed(jdbc);
        committedAtInspect.clear();
        doAnswer(invocation -> {
                    RemediationDraftRequest request = invocation.getArgument(0);
                    return new RemediationDraftResponse(
                            1,
                            request.correlationId(),
                            RemediationDraftResponse.RemediationIntentType.PROPOSE_REMEDIATION,
                            new RemediationDraftResponse.Proposal(
                                    "恢复统计消费",
                                    "重新启动已停止的统计消费者。",
                                    new RemediationDraftResponse.Action(
                                            "service.restart",
                                            seeded.consumer(),
                                            new ServiceRestartParametersV1(),
                                            "重新启动统计消费者",
                                            "统计消费短暂中断后恢复。")));
                })
                .when(ai)
                .draftRemediation(any());
        doAnswer(invocation -> new ServiceRestartExecutor.Resolved(CONTAINER_ID))
                .when(executor)
                .resolveTarget(any(), anyString(), any());
        doAnswer(invocation -> new ServiceRestartExecutor.Uncertain(ErrorCode.TIMEOUT, "timeout"))
                .when(executor)
                .restart(any(), anyString(), any());
    }

    /**
     * 04 §82、ACC-FINAL-07：restart 响应丢失后进入核对；登记（次数、尝试时间、截止时间与 ATTEMPTED 事件）在 inspect 之前已提交，inspect
     * 不在事务内；同一容器 RUNNING 且启动时间晚于 started_at → SUCCEEDED（核对结果载荷）、方案 EXECUTED，Incident 仍 EXECUTING；
     * restart 只发出过一次。
     */
    @Test
    void anUncertainRestartIsConfirmedByAReadOnlyInspection() {
        long executionId = approvedExecution();
        inspections(
                executionId, startedAt -> new Inspected(CONTAINER_ID, RuntimeState.RUNNING, startedAt.plusMillis(1)));

        worker.runActionExecution(executionId);

        assertThat(committedAtInspect).singleElement().satisfies(seen -> {
            assertThat(seen).containsEntry("reconciliation_attempt_count", 1L).containsEntry("in_transaction", false);
            assertThat(seen.get("last_reconciliation_at")).isNotNull();
            assertThat(seen.get("attempted_events")).isEqualTo(1L);
            assertThat(Duration.between(
                            instant(seen.get("last_reconciliation_at")),
                            instant(seen.get("reconciliation_deadline_at"))))
                    .isEqualTo(Duration.ofSeconds(60));
        });
        assertThat(execution(executionId))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("result_schema_name", ServiceRestartReconciliationResultV1.SCHEMA_NAME)
                .containsEntry("error_code", null)
                .containsEntry("reconciliation_attempt_count", 1L);
        ServiceRestartReconciliationResultV1 result = codecs.decode(
                ServiceRestartReconciliationResultV1.SCHEMA_NAME,
                1,
                jdbc.queryForObject(
                        "SELECT CAST(result_payload AS CHAR) FROM action_execution WHERE id = ?",
                        String.class,
                        executionId),
                ServiceRestartReconciliationResultV1.class);
        assertThat(result.containerId()).isEqualTo(CONTAINER_ID);
        assertThat(result.attemptNo()).isEqualTo(1);
        assertThat(result.serviceStartedAt()).isAfter(result.executionStartedAt());
        assertThat(jdbc.queryForObject(
                        "SELECT CONCAT(status, '/', verification_no) FROM recovery_verification"
                                + " WHERE action_execution_id = ?",
                        String.class,
                        executionId))
                .as("核对确认成功同样创建 Verification（TASK-080）")
                .isEqualTo("PENDING/1");
        assertThat(planStatus()).isEqualTo("EXECUTED");
        assertThat(incident()).isEqualTo("VERIFYING/10");
        assertThat(events())
                .containsExactly(
                        "ACTION_EXECUTION_STARTED/-",
                        "ACTION_EXECUTION_RECONCILIATION_ATTEMPTED/-",
                        "ACTION_EXECUTION_SUCCEEDED/-");
        verify(executor, times(1)).restart(any(), anyString(), any());
    }

    /**
     * 04 §82：不猜测成功——其他容器、启动时间不晚于 started_at、容器未运行都不确认；次数耗尽后 FAILED / EXECUTION_RESULT_UNCERTAIN、
     * 方案 EXECUTED、Incident 回到 DIAGNOSED；每次尝试都有 ATTEMPTED 事件，尝试间隔不短于配置，restart 只有最初一次。
     */
    @Test
    void unconfirmedInspectionsExhaustTheAttemptsAndFailAsUncertain() {
        long executionId = approvedExecution();
        inspections(
                executionId,
                startedAt -> new Inspected(OTHER_ID, RuntimeState.RUNNING, startedAt.plusSeconds(1)),
                startedAt -> new Inspected(CONTAINER_ID, RuntimeState.RUNNING, startedAt),
                startedAt -> new Inspected(CONTAINER_ID, RuntimeState.STOPPED, startedAt.plusSeconds(1)));

        worker.runActionExecution(executionId);

        assertThat(committedAtInspect)
                .extracting(seen -> seen.get("reconciliation_attempt_count"))
                .containsExactly(1L, 2L, 3L);
        assertThat(Duration.between(
                        instant(committedAtInspect.get(0).get("last_reconciliation_at")),
                        instant(committedAtInspect.get(2).get("last_reconciliation_at"))))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(execution(executionId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "EXECUTION_RESULT_UNCERTAIN")
                .containsEntry("result_schema_name", null)
                .containsEntry("reconciliation_attempt_count", 3L);
        assertThat(planStatus()).isEqualTo("EXECUTED");
        assertThat(incident()).isEqualTo("DIAGNOSED/10");
        assertThat(events())
                .containsExactly(
                        "ACTION_EXECUTION_STARTED/-",
                        "ACTION_EXECUTION_RECONCILIATION_ATTEMPTED/-",
                        "ACTION_EXECUTION_RECONCILIATION_ATTEMPTED/-",
                        "ACTION_EXECUTION_RECONCILIATION_ATTEMPTED/-",
                        "ACTION_EXECUTION_FAILED/EXECUTION_RESULT_UNCERTAIN");
        verify(executor, times(1)).restart(any(), anyString(), any());
    }

    /** 读取失败或启动时间缺失同样不确认，但只要还有额度就继续只读核对，后续确认即 SUCCEEDED。 */
    @Test
    void aLaterAttemptWithinTheBudgetCanStillConfirm() {
        long executionId = approvedExecution();
        inspections(
                executionId,
                startedAt -> new NotInspected(ErrorCode.TIMEOUT, "timeout"),
                startedAt -> new Inspected(CONTAINER_ID, RuntimeState.RUNNING, null),
                startedAt -> new Inspected(CONTAINER_ID, RuntimeState.RUNNING, startedAt.plusSeconds(2)));

        worker.runActionExecution(executionId);

        assertThat(execution(executionId))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("reconciliation_attempt_count", 3L);
        assertThat(incident()).isEqualTo("VERIFYING/10");
        assertThat(events()).last().isEqualTo("ACTION_EXECUTION_SUCCEEDED/-");
        verify(executor, times(1)).restart(any(), anyString(), any());
    }

    /**
     * 01 §26、ACC-FINAL-08：登记后崩溃（inspect 途中线程异常退出）消耗该次；之后再唤醒只用剩余额度，不重发 restart，也不刷新上限与
     * 截止时间；额度用完收束为 UNCERTAIN。
     */
    @Test
    void aCrashAfterRegistrationConsumesOnlyThatAttempt() {
        long executionId = approvedExecution();
        doAnswer(invocation -> {
                    throw new IllegalStateException("process died during inspect");
                })
                .when(inspector)
                .inspect(any(), anyString(), any());

        assertThatThrownBy(() -> worker.runActionExecution(executionId)).isInstanceOf(IllegalStateException.class);

        Map<String, Object> afterCrash = reconciliation(executionId);
        assertThat(afterCrash)
                .containsEntry("status", "RUNNING")
                .containsEntry("reconciliation_attempt_count", 1L)
                .containsEntry("max_reconciliation_attempts", 3L);
        inspections(executionId, startedAt -> new NotInspected(ErrorCode.CONNECTION_FAILED, "unreachable"));

        worker.runActionExecution(executionId);

        assertThat(committedAtInspect)
                .extracting(seen -> seen.get("reconciliation_attempt_count"))
                .containsExactly(2L, 3L);
        assertThat(reconciliation(executionId))
                .containsEntry("status", "FAILED")
                .containsEntry("reconciliation_attempt_count", 3L)
                .containsEntry("max_reconciliation_attempts", 3L)
                .containsEntry("reconciliation_deadline_at", afterCrash.get("reconciliation_deadline_at"));
        assertThat(execution(executionId)).containsEntry("error_code", "EXECUTION_RESULT_UNCERTAIN");
        assertThat(incident()).isEqualTo("DIAGNOSED/10");
        verify(executor, times(1)).restart(any(), anyString(), any());
    }

    /** 已登记次数达到上限，或冻结的截止时间已过：不再 inspect，直接收束为 UNCERTAIN（04 §82）。 */
    @Test
    void anExhaustedOrExpiredBudgetFailsWithoutInspecting() {
        for (String exhaustion : List.of(
                "reconciliation_attempt_count = max_reconciliation_attempts",
                "reconciliation_deadline_at = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND")) {
            seed();
            long executionId = crashedAfterFirstRegistration();
            jdbc.update(
                    "UPDATE action_execution SET " + exhaustion + ", last_reconciliation_at = UTC_TIMESTAMP(3)"
                            + " - INTERVAL 10 SECOND WHERE id = ?",
                    executionId);
            inspections(
                    executionId,
                    startedAt -> new Inspected(CONTAINER_ID, RuntimeState.RUNNING, startedAt.plusSeconds(1)));

            worker.runActionExecution(executionId);

            assertThat(committedAtInspect).as(exhaustion).isEmpty();
            assertThat(execution(executionId))
                    .as(exhaustion)
                    .containsEntry("status", "FAILED")
                    .containsEntry("error_code", "EXECUTION_RESULT_UNCERTAIN");
            assertThat(incident()).isEqualTo("DIAGNOSED/10");
            assertThat(events()).last().isEqualTo("ACTION_EXECUTION_FAILED/EXECUTION_RESULT_UNCERTAIN");
        }
    }

    /** 同一 RUNNING 被并发唤醒：登记是条件更新，次数不超过快照上限，事件与登记一一对应，只收束一次，从不 restart。 */
    @Test
    void concurrentReconcilersNeverExceedTheSnapshotLimit() throws Exception {
        long executionId = runningWithoutReconciliation();
        doAnswer(invocation -> {
                    Thread.sleep(100);
                    return new NotInspected(ErrorCode.TIMEOUT, "timeout");
                })
                .when(inspector)
                .inspect(any(), anyString(), any());
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> workers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            workers.add(CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
                worker.runActionExecution(executionId);
            }));
        }
        start.countDown();
        for (var call : workers) {
            call.get(60, TimeUnit.SECONDS);
        }

        assertThat(execution(executionId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "EXECUTION_RESULT_UNCERTAIN")
                .containsEntry("reconciliation_attempt_count", 3L);
        assertThat(events())
                .filteredOn(event -> event.startsWith("ACTION_EXECUTION_RECONCILIATION_ATTEMPTED"))
                .hasSize(3);
        assertThat(events())
                .filteredOn(event -> event.startsWith("ACTION_EXECUTION_FAILED"))
                .hasSize(1);
        verify(inspector, times(3)).inspect(any(), anyString(), any());
        verify(executor, never()).restart(any(), anyString(), any());
    }

    /**
     * 真实 Docker：restart 实际已在远端完成但响应“丢失”（执行器真实调用后报告未知），核对以正式只读检查器读到同一容器在 started_at
     * 之后启动，确认 SUCCEEDED；Engine API 只收到一次 restart。
     */
    @Test
    void aLostResponseOfARealRestartIsConfirmedOnTheRealContainer() throws Exception {
        String name = "opspilot-t072-" + ProcessHandle.current().pid();
        docker("rm", "-f", name);
        docker("run", "-d", "--name", name, "redis:7.4.5");
        try {
            jdbc.update(
                    "UPDATE resource_binding SET selector_payload = JSON_OBJECT('containerName', ?)"
                            + " WHERE managed_resource_id = ?",
                    name,
                    seeded.consumer());
            doAnswer(invocation -> invocation.callRealMethod()).when(executor).resolveTarget(any(), anyString(), any());
            doAnswer(invocation -> {
                        invocation.callRealMethod();
                        return new ServiceRestartExecutor.Uncertain(ErrorCode.TIMEOUT, "response lost");
                    })
                    .when(executor)
                    .restart(any(), anyString(), any());
            String startedBefore = docker("inspect", "-f", "{{.State.StartedAt}}", name);
            long executionId = approvedExecution();

            worker.runActionExecution(executionId);

            assertThat(execution(executionId))
                    .containsEntry("status", "SUCCEEDED")
                    .containsEntry("result_schema_name", ServiceRestartReconciliationResultV1.SCHEMA_NAME)
                    .containsEntry("reconciliation_attempt_count", 1L);
            assertThat(context(executionId).containerId()).isEqualTo(docker("inspect", "-f", "{{.Id}}", name));
            assertThat(docker("inspect", "-f", "{{.State.StartedAt}}", name)).isNotEqualTo(startedBefore);
            verify(executor, times(1)).restart(any(), anyString(), any());
            verify(inspector, times(1)).inspect(any(), anyString(), any());
        } finally {
            docker("rm", "-f", name);
        }
    }

    // ---------------------------------------------------------------- helpers

    private long approvedExecution() {
        seeded.activateRecoveryPolicy(recoveryPolicies, "consumer-recovery");
        long approvalId = remediations
                .requestRemediation(new RequestRemediationCommand(seeded.incidentKey(), 7, "demo-user"))
                .approvalId();
        long executionId = approvals
                .approve(new ApprovalDecisionCommand(approvalId, 0, 8, null, "demo-user"))
                .execution()
                .executionId();
        jdbc.update("DELETE FROM incident_timeline_event");
        return executionId;
    }

    /** RUNNING 且尚未登记核对：例如 restart 途中进程退出（准入已提交，结果未落账）。 */
    private long runningWithoutReconciliation() {
        long executionId = approvedExecution();
        var pending = executions.findById(executionId).orElseThrow();
        ServiceRestartExecutionContextV1 context = context(executionId);
        ServiceRestartExecutionContextV1 admitted = new ServiceRestartExecutionContextV1(
                context.targetResourceId(),
                context.resourceBindingId(),
                context.dataSourceConnectionId(),
                context.containerName(),
                CONTAINER_ID);
        assertThat(executions.markRunning(
                        executionId,
                        pending.lockVersion(),
                        codecs.encode(ServiceRestartExecutionContextV1.SCHEMA_NAME, 1, admitted),
                        Instant.now().truncatedTo(ChronoUnit.MILLIS)))
                .isTrue();
        return executionId;
    }

    /** 首次登记后 inspect 途中崩溃：RUNNING、已登记 1 次。 */
    private long crashedAfterFirstRegistration() {
        long executionId = approvedExecution();
        doAnswer(invocation -> {
                    throw new IllegalStateException("process died during inspect");
                })
                .when(inspector)
                .inspect(any(), anyString(), any());
        assertThatThrownBy(() -> worker.runActionExecution(executionId)).isInstanceOf(IllegalStateException.class);
        return executionId;
    }

    /** 按顺序返回给定观察（最后一个重复使用），每次记录 inspect 时已提交的核对状态；函数参数为 execution.started_at。 */
    @SafeVarargs
    private void inspections(long executionId, Function<Instant, RuntimeInspection>... answers) {
        Deque<Function<Instant, RuntimeInspection>> queue = new ArrayDeque<>(List.of(answers));
        doAnswer(invocation -> {
                    Map<String, Object> seen = new java.util.HashMap<>(jdbc.queryForMap(
                            "SELECT reconciliation_attempt_count, last_reconciliation_at, reconciliation_deadline_at,"
                                    + " started_at, (SELECT COUNT(*) FROM incident_timeline_event"
                                    + " WHERE event_type = 'ACTION_EXECUTION_RECONCILIATION_ATTEMPTED') AS attempted_events"
                                    + " FROM action_execution WHERE id = ?",
                            executionId));
                    seen.replaceAll((key, value) -> value instanceof Number number ? number.longValue() : value);
                    seen.put("in_transaction", TransactionSynchronizationManager.isActualTransactionActive());
                    committedAtInspect.add(seen);
                    assertThat((Instant) invocation.getArgument(2))
                            .isBeforeOrEqualTo(Instant.now().plusSeconds(5));
                    Instant startedAt = instant(seen.get("started_at"));
                    Function<Instant, RuntimeInspection> answer = queue.size() > 1 ? queue.poll() : queue.peek();
                    return answer.apply(startedAt);
                })
                .when(inspector)
                .inspect(any(), anyString(), any());
    }

    /** DATETIME(3) 按 UTC 存储；驱动可能返回 LocalDateTime 或 Timestamp。 */
    private static Instant instant(Object value) {
        return value instanceof LocalDateTime local ? local.toInstant(ZoneOffset.UTC) : ((Timestamp) value).toInstant();
    }

    private Map<String, Object> execution(long executionId) {
        Map<String, Object> row = new java.util.HashMap<>(jdbc.queryForMap(
                "SELECT status, result_schema_name, error_code, reconciliation_attempt_count"
                        + " FROM action_execution WHERE id = ?",
                executionId));
        row.replaceAll((key, value) -> value instanceof Number number ? number.longValue() : value);
        return row;
    }

    private Map<String, Object> reconciliation(long executionId) {
        Map<String, Object> row = new java.util.HashMap<>(jdbc.queryForMap(
                "SELECT status, reconciliation_attempt_count, max_reconciliation_attempts, reconciliation_deadline_at"
                        + " FROM action_execution WHERE id = ?",
                executionId));
        row.replaceAll((key, value) -> value instanceof Number number ? number.longValue() : value);
        return row;
    }

    private ServiceRestartExecutionContextV1 context(long executionId) {
        return codecs.decode(
                ServiceRestartExecutionContextV1.SCHEMA_NAME,
                1,
                jdbc.queryForObject(
                        "SELECT CAST(execution_context_payload AS CHAR) FROM action_execution WHERE id = ?",
                        String.class,
                        executionId),
                ServiceRestartExecutionContextV1.class);
    }

    private String planStatus() {
        return jdbc.queryForObject("SELECT status FROM remediation_plan", String.class);
    }

    private String incident() {
        return jdbc.queryForObject(
                "SELECT CONCAT(status, '/', lock_version) FROM incident WHERE id = ?",
                String.class,
                seeded.investigation().incidentId());
    }

    /** 事件类型/错误码（无则 -），按写入顺序。 */
    private List<String> events() {
        return jdbc.queryForList(
                "SELECT CONCAT(event_type, '/', COALESCE(NULLIF(payload->>'$.errorCode', 'null'), '-')) FROM incident_timeline_event"
                        + " ORDER BY id",
                String.class);
    }

    /** 测试夹具用 docker CLI 准备与核对容器；被测代码只经 Engine API。 */
    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exit = process.waitFor();
        if (!arguments[0].equals("rm")) {
            assertThat(exit).as(String.join(" ", command) + ": " + output).isZero();
        }
        return output;
    }
}
