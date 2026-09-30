package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWorkSource;
import io.github.ismoyuan.opspilot.application.dispatch.StartupRecoveryCoordinator;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRecoveryService;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRepository;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionService;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutionContextV1;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartResultV1;
import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyActivationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySelector;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyValidator;
import io.github.ismoyuan.opspilot.application.remediation.RemediationActions;
import io.github.ismoyuan.opspilot.application.remediation.RemediationApplicationService;
import io.github.ismoyuan.opspilot.application.remediation.RemediationDraftContextBuilder;
import io.github.ismoyuan.opspilot.application.remediation.RemediationProposalValidator;
import io.github.ismoyuan.opspilot.application.remediation.RequestRemediationCommand;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.infrastructure.dispatch.InProcessWorkDispatcher;
import io.github.ismoyuan.opspilot.infrastructure.dispatch.SingleFlightRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 08 TASK-073：真实 MySQL＋真实 Execution 来源与进程内派发器上的启动恢复与补派发（07 §51）。批准后的派发被替身吞掉以模拟“提交后、
 * 派发前崩溃”；启动协调器与派发器按正式组件手工组装（线程池可替换为先拒绝一次的版本）。验证 PENDING 重新派发且只发一次 CHANGE、
 * RUNNING 只恢复剩余只读核对且绝不重发、终态不派发、重复唤醒被单飞合并，以及启动扫描不刷新快照、次数上限与截止时间。
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
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    ClockConfiguration.class
})
class ExecutionStartupRecoveryIntegrationTest {

    static final String CONTAINER_ID = "c".repeat(64);

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
    List<DispatchableWorkSource> workSources;

    @Autowired
    Clock clock;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AiDecisionPort ai;

    /** 批准事务提交后的唤醒被吞掉：模拟派发之前进程退出。 */
    @MockitoBean
    WorkDispatcher lostDispatch;

    @MockitoSpyBean
    ServiceRestartExecutor executor;

    @MockitoSpyBean
    ServiceRuntimeInspector inspector;

    RemediationFixture seeded;
    InProcessWorkDispatcher dispatcher;

    @BeforeEach
    void seed() {
        seeded = RemediationFixture.seed(jdbc);
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
        doAnswer(invocation -> new ServiceRestartExecutor.Succeeded(
                        new ServiceRestartResultV1("DOCKER", CONTAINER_ID, Instant.now(), Instant.now())))
                .when(executor)
                .restart(any(), anyString(), any());
    }

    @AfterEach
    void stopDispatcher() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    /**
     * PENDING 提交后、派发前崩溃：启动恢复经来源唤醒，只有条件更新获胜者发出一次 CHANGE；之后的启动扫描与补派发不再派发终态，
     * restart 仍只有一次。
     */
    @Test
    void aPendingExecutionWhoseDispatchWasLostRunsOnceAfterStartup() throws Exception {
        long executionId = approvedExecution();
        Map<String, Object> frozen = frozenContract(executionId);
        StartupRecoveryCoordinator coordinator = coordinator(pool());

        assertThat(coordinator.recoverAfterStartup()).isEqualTo(1);
        awaitStatus(executionId, "SUCCEEDED");
        assertThat(coordinator.redispatchPending()).isZero();
        assertThat(new StartupRecoveryCoordinator(List.of(executionWork()), List.of(), dispatcher, clock)
                        .recoverAfterStartup())
                .isZero();

        verify(executor, times(1)).restart(any(), anyString(), any());
        Map<String, Object> after = frozenContract(executionId);
        assertThat(after.get("started_at")).as("set once by the admission").isNotNull();
        after.remove("started_at");
        frozen.remove("started_at");
        assertThat(after).isEqualTo(frozen);
    }

    /**
     * CHANGE 响应丢失（进程在 restart 途中退出，RUNNING 且从未核对）：启动后不解析目标、不 restart，只做只读核对；确认后 SUCCEEDED，
     * 快照与次数上限不变。
     */
    @Test
    void aRunningExecutionIsOnlyReconciledAfterStartup() throws Exception {
        long executionId = runningWithoutReconciliation();
        Map<String, Object> frozen = frozenContract(executionId);
        doAnswer(invocation -> new ServiceRuntimeInspector.Inspected(
                        CONTAINER_ID, RuntimeState.RUNNING, Instant.now().plusSeconds(3600)))
                .when(inspector)
                .inspect(any(), anyString(), any());

        assertThat(coordinator(pool()).recoverAfterStartup()).isEqualTo(1);
        awaitStatus(executionId, "SUCCEEDED");

        verify(executor, never()).resolveTarget(any(), anyString(), any());
        verify(executor, never()).restart(any(), anyString(), any());
        verify(inspector, times(1)).inspect(any(), anyString(), any());
        assertThat(frozenContract(executionId)).isEqualTo(frozen);
        assertThat(reconciliation(executionId)).containsEntry("reconciliation_attempt_count", 1L);
    }

    /**
     * 核对登记后崩溃（已登记 2 次、截止时间已冻结）：启动后只用剩余的 1 次，不刷新上限与截止时间，耗尽后 FAILED /
     * EXECUTION_RESULT_UNCERTAIN；从不 restart。
     */
    @Test
    void aCrashAfterReconciliationRegistrationResumesOnlyTheRemainingAttempts() throws Exception {
        long executionId = runningWithoutReconciliation();
        jdbc.update(
                "UPDATE action_execution SET reconciliation_attempt_count = 2,"
                        + " last_reconciliation_at = UTC_TIMESTAMP(3) - INTERVAL 10 SECOND,"
                        + " reconciliation_deadline_at = UTC_TIMESTAMP(3) + INTERVAL 50 SECOND WHERE id = ?",
                executionId);
        Map<String, Object> before = reconciliation(executionId);
        Map<String, Object> frozen = frozenContract(executionId);
        doAnswer(invocation -> new ServiceRuntimeInspector.NotInspected(ErrorCode.TIMEOUT, "timeout"))
                .when(inspector)
                .inspect(any(), anyString(), any());

        coordinator(pool()).recoverAfterStartup();
        awaitStatus(executionId, "FAILED");

        verify(inspector, times(1)).inspect(any(), anyString(), any());
        verify(executor, never()).restart(any(), anyString(), any());
        assertThat(reconciliation(executionId))
                .containsEntry("reconciliation_attempt_count", 3L)
                .containsEntry("max_reconciliation_attempts", before.get("max_reconciliation_attempts"))
                .containsEntry("reconciliation_deadline_at", before.get("reconciliation_deadline_at"))
                .containsEntry("error_code", "EXECUTION_RESULT_UNCERTAIN");
        assertThat(frozenContract(executionId)).isEqualTo(frozen);
    }

    /** 线程池拒绝不丢弃已提交事实：Execution 保持 PENDING、未发 CHANGE；下一次周期补派发唤醒并执行一次。 */
    @Test
    void aRejectedDispatchIsRecoveredByTheNextRescan() throws Exception {
        long executionId = approvedExecution();
        AtomicBoolean rejectNext = new AtomicBoolean(true);
        ThreadPoolExecutor rejectingOnce =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
                    @Override
                    public void execute(Runnable command) {
                        if (rejectNext.getAndSet(false)) {
                            throw new RejectedExecutionException("pool full");
                        }
                        super.execute(command);
                    }
                };
        StartupRecoveryCoordinator coordinator = coordinator(rejectingOnce);

        coordinator.recoverAfterStartup();
        assertThat(status(executionId)).isEqualTo("PENDING");
        verify(executor, never()).restart(any(), anyString(), any());

        assertThat(coordinator.redispatchPending()).isEqualTo(1);
        awaitStatus(executionId, "SUCCEEDED");
        verify(executor, times(1)).restart(any(), anyString(), any());
    }

    /** 同一 Execution 在 Worker 运行期间被启动扫描与补派发重复唤醒：派发器单飞合并，CHANGE 只发一次。 */
    @Test
    void duplicateWakeupsOfARunningWorkerAreCoalesced() throws Exception {
        long executionId = approvedExecution();
        CountDownLatch restarting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    restarting.countDown();
                    release.await(30, TimeUnit.SECONDS);
                    return new ServiceRestartExecutor.Succeeded(
                            new ServiceRestartResultV1("DOCKER", CONTAINER_ID, Instant.now(), Instant.now()));
                })
                .when(executor)
                .restart(any(), anyString(), any());
        StartupRecoveryCoordinator coordinator = coordinator(pool());

        coordinator.recoverAfterStartup();
        assertThat(restarting.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(status(executionId)).isEqualTo("RUNNING");
        coordinator.redispatchPending();
        coordinator.redispatchPending();
        release.countDown();
        awaitStatus(executionId, "SUCCEEDED");
        dispatcher.shutdown();

        verify(executor, times(1)).restart(any(), anyString(), any());
        verify(inspector, never()).inspect(any(), anyString(), any());
    }

    // ---------------------------------------------------------------- helpers

    private StartupRecoveryCoordinator coordinator(ThreadPoolExecutor pool) {
        dispatcher = new InProcessWorkDispatcher(
                pool,
                new SingleFlightRegistry(),
                (incidentId, runNo) -> {
                    throw new AssertionError("no investigation is expected");
                },
                worker,
                verificationId -> {
                    throw new AssertionError("no verification is expected");
                });
        return new StartupRecoveryCoordinator(List.of(executionWork()), List.of(), dispatcher, clock);
    }

    /** 正式来源中的 Execution 工作；夹具里另有与本用例无关的 INVESTIGATING Incident，不交给派发器。 */
    private DispatchableWorkSource executionWork() {
        return () -> workSources.stream()
                .flatMap(source -> source.findDispatchable().stream())
                .filter(DispatchableWork.ActionExecution.class::isInstance)
                .toList();
    }

    private static ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    private long approvedExecution() {
        seeded.activateRecoveryPolicy(recoveryPolicies, "consumer-recovery");
        long approvalId = remediations
                .requestRemediation(new RequestRemediationCommand(seeded.incidentKey(), 7, "demo-user"))
                .approvalId();
        return approvals
                .approve(new ApprovalDecisionCommand(approvalId, 0, 8, null, "demo-user"))
                .execution()
                .executionId();
    }

    /** 准入已提交、restart 途中进程退出：RUNNING，已保存容器身份，从未核对。 */
    private long runningWithoutReconciliation() {
        long executionId = approvedExecution();
        var pending = executions.findById(executionId).orElseThrow();
        ServiceRestartExecutionContextV1 context = codecs.decode(
                ServiceRestartExecutionContextV1.SCHEMA_NAME,
                1,
                pending.executionContextPayload(),
                ServiceRestartExecutionContextV1.class);
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

    /** 创建时冻结、启动恢复不得改变的合同：策略快照与版本、次数上限、准入时间。 */
    private Map<String, Object> frozenContract(long executionId) {
        return new HashMap<>(jdbc.queryForMap(
                "SELECT recovery_policy_id, recovery_policy_version, CAST(recovery_policy_snapshot AS CHAR) AS snapshot,"
                        + " max_reconciliation_attempts, started_at FROM action_execution WHERE id = ?",
                executionId));
    }

    private Map<String, Object> reconciliation(long executionId) {
        Map<String, Object> row = new HashMap<>(jdbc.queryForMap(
                "SELECT reconciliation_attempt_count, max_reconciliation_attempts, reconciliation_deadline_at, error_code"
                        + " FROM action_execution WHERE id = ?",
                executionId));
        row.replaceAll((key, value) -> value instanceof Number number ? number.longValue() : value);
        return row;
    }

    private String status(long executionId) {
        return jdbc.queryForObject("SELECT status FROM action_execution WHERE id = ?", String.class, executionId);
    }

    private void awaitStatus(long executionId, String expected) throws InterruptedException {
        for (int i = 0; i < 300 && !expected.equals(status(executionId)); i++) {
            Thread.sleep(100);
        }
        assertThat(status(executionId)).isEqualTo(expected);
    }
}
