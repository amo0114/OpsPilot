package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
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
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionRepository;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionService;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutionContextV1;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartResultV1;
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
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * 08 TASK-071：真实 MySQL 上的执行 Worker。经真实 request-remediation 与批准得到 PENDING Execution，再由 Worker 处理；Docker 执行器
 * 为正式 Bean 的 Spy（按用例替换结果，一个用例对真实容器重启）。验证外部调用不在事务内、唯一准入、各结果分支与准入前失败。
 */
@SpringBootTest
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
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    ClockConfiguration.class
})
class ActionExecutionWorkerIntegrationTest {

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
    JdbcTemplate jdbc;

    @MockitoBean
    AiDecisionPort ai;

    @MockitoBean
    WorkDispatcher dispatcher;

    @MockitoSpyBean
    ServiceRestartExecutor executor;

    RemediationFixture seeded;
    final List<Boolean> transactionActiveDuringDocker = new ArrayList<>();

    @BeforeEach
    void seed() {
        seeded = RemediationFixture.seed(jdbc);
        transactionActiveDuringDocker.clear();
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
        doAnswer(invocation -> {
                    transactionActiveDuringDocker.add(TransactionSynchronizationManager.isActualTransactionActive());
                    return new ServiceRestartExecutor.Resolved(CONTAINER_ID);
                })
                .when(executor)
                .resolveTarget(any(), anyString(), any());
    }

    /**
     * 04 §79、06 §109：准入后发出一次 restart，成功落账为 SUCCEEDED（结果、容器 id、started_at），方案 EXECUTED，
     * STARTED 与 SUCCEEDED 两个事件；Incident 仍 EXECUTING（Verification 属 TASK-080）。解析与重启都不在事务内。
     */
    @Test
    void aSuccessfulRestartIsRecordedAsTheOperationSucceeded() {
        long executionId = approvedExecution();
        restartAnswers(new ServiceRestartExecutor.Succeeded(new ServiceRestartResultV1(
                "DOCKER", CONTAINER_ID, Instant.parse("2026-09-30T10:00:00Z"), Instant.parse("2026-09-30T10:00:03Z"))));

        worker.runActionExecution(executionId);

        assertThat(transactionActiveDuringDocker).containsExactly(false, false);
        assertThat(execution(executionId))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("result_schema_name", "service.restart.result")
                .containsEntry("error_code", null)
                .containsEntry("has_started", "YES");
        assertThat(context(executionId).containerId()).isEqualTo(CONTAINER_ID);
        assertThat(planStatus()).isEqualTo("EXECUTED");
        assertThat(incident()).isEqualTo("EXECUTING/9");
        assertThat(events()).containsExactly("ACTION_EXECUTION_STARTED/-", "ACTION_EXECUTION_SUCCEEDED/-");
        verify(executor, times(1)).restart(any(), anyString(), any());
    }

    /** 04 §79：明确失败 FAILED（错误码与固定文案）、方案 EXECUTED、Incident 回到 DIAGNOSED。 */
    @Test
    void anExplicitFailureReturnsTheIncidentToDiagnosed() {
        long executionId = approvedExecution();
        restartAnswers(new ServiceRestartExecutor.Failed(
                ErrorCode.PROVIDER_UNAVAILABLE, "Docker Engine failed to restart the container"));

        worker.runActionExecution(executionId);

        assertThat(execution(executionId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "PROVIDER_UNAVAILABLE")
                .containsEntry("has_started", "YES");
        assertThat(planStatus()).isEqualTo("EXECUTED");
        assertThat(incident()).isEqualTo("DIAGNOSED/10");
        assertThat(events())
                .containsExactly("ACTION_EXECUTION_STARTED/-", "ACTION_EXECUTION_FAILED/PROVIDER_UNAVAILABLE");
    }

    /** 04 §82：结果未知保持 RUNNING，不伪造成功或失败；再次唤醒不重发 CHANGE（RUNNING 不是“未发送”）。 */
    @Test
    void anUncertainResultStaysRunningAndIsNeverResent() {
        long executionId = approvedExecution();
        restartAnswers(new ServiceRestartExecutor.Uncertain(ErrorCode.TIMEOUT, "timeout"));

        worker.runActionExecution(executionId);
        worker.runActionExecution(executionId);

        assertThat(execution(executionId)).containsEntry("status", "RUNNING").containsEntry("error_code", null);
        assertThat(planStatus()).isEqualTo("ACTIVE");
        assertThat(incident()).isEqualTo("EXECUTING/9");
        assertThat(events()).containsExactly("ACTION_EXECUTION_STARTED/-");
        verify(executor, times(1)).restart(any(), anyString(), any());
    }

    /**
     * 04 §45、06 §107：RUNNING 准入前的失败没有发出 CHANGE——容器无法解析、批准后绑定被停用或改指向其他容器——Execution FAILED
     * （无 started_at）、方案 CANCELLED、Incident DIAGNOSED、ACTION_EXECUTION_FAILED。
     */
    @Test
    void failuresBeforeAdmissionNeverSendTheChange() {
        for (Object[] scenario : List.of(
                new Object[] {"unresolved", "RESOURCE_NOT_FOUND"},
                new Object[] {"disabled", "CAPABILITY_NOT_BOUND"},
                new Object[] {"retargeted", "INVALID_BINDING"})) {
            seed();
            long executionId = approvedExecution();
            switch ((String) scenario[0]) {
                case "unresolved" ->
                    doReturn(new ServiceRestartExecutor.Unresolved(
                                    ErrorCode.RESOURCE_NOT_FOUND, "Container does not exist"))
                            .when(executor)
                            .resolveTarget(any(), anyString(), any());
                case "disabled" ->
                    jdbc.update(
                            "UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ?"
                                    + " AND capability_key = 'service.restart'",
                            seeded.consumer());
                default ->
                    jdbc.update(
                            "UPDATE resource_binding SET selector_payload = JSON_OBJECT('containerName', 'other')"
                                    + " WHERE managed_resource_id = ?",
                            seeded.consumer());
            }

            worker.runActionExecution(executionId);

            assertThat(execution(executionId))
                    .as((String) scenario[0])
                    .containsEntry("status", "FAILED")
                    .containsEntry("error_code", scenario[1])
                    .containsEntry("has_started", "NO");
            assertThat(planStatus()).isEqualTo("CANCELLED");
            assertThat(incident()).isEqualTo("DIAGNOSED/10");
            assertThat(events()).containsExactly("ACTION_EXECUTION_FAILED/" + scenario[1]);
            verify(executor, never()).restart(any(), anyString(), any());
        }
    }

    /** 04 §82：同一 Execution 被并发唤醒时只有 PENDING → RUNNING 的一个获胜者发出 CHANGE。 */
    @Test
    void concurrentWorkersSendTheChangeExactlyOnce() throws Exception {
        long executionId = approvedExecution();
        doAnswer(invocation -> {
                    Thread.sleep(200);
                    return new ServiceRestartExecutor.Succeeded(
                            new ServiceRestartResultV1("DOCKER", CONTAINER_ID, Instant.now(), Instant.now()));
                })
                .when(executor)
                .restart(any(), anyString(), any());
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
            call.get(30, TimeUnit.SECONDS);
        }

        verify(executor, times(1)).restart(any(), anyString(), any());
        assertThat(execution(executionId)).containsEntry("status", "SUCCEEDED");
        assertThat(events()).containsExactly("ACTION_EXECUTION_STARTED/-", "ACTION_EXECUTION_SUCCEEDED/-");
    }

    /** 06 §106：真实 Docker——Worker 以正式执行器解析配置的容器并经 Engine API 重启它，落账其真实 id。 */
    @Test
    void theRealExecutorRestartsTheConfiguredContainer() throws Exception {
        String name = "opspilot-t071-" + ProcessHandle.current().pid();
        docker("rm", "-f", name);
        docker("run", "-d", "--name", name, "redis:7.4.5");
        try {
            jdbc.update(
                    "UPDATE resource_binding SET selector_payload = JSON_OBJECT('containerName', ?)"
                            + " WHERE managed_resource_id = ?",
                    name,
                    seeded.consumer());
            doAnswer(invocation -> invocation.callRealMethod()).when(executor).resolveTarget(any(), anyString(), any());
            String startedBefore = docker("inspect", "-f", "{{.State.StartedAt}}", name);
            long executionId = approvedExecution();

            worker.runActionExecution(executionId);

            assertThat(execution(executionId)).containsEntry("status", "SUCCEEDED");
            assertThat(context(executionId).containerId()).isEqualTo(docker("inspect", "-f", "{{.Id}}", name));
            assertThat(docker("inspect", "-f", "{{.State.StartedAt}}", name)).isNotEqualTo(startedBefore);
        } finally {
            docker("rm", "-f", name);
        }
    }

    /** 终态 Execution 再被唤醒不做任何事。 */
    @Test
    void terminalExecutionsAreIgnored() {
        long executionId = approvedExecution();
        restartAnswers(new ServiceRestartExecutor.Failed(ErrorCode.QUERY_REJECTED, "rejected"));
        worker.runActionExecution(executionId);

        worker.runActionExecution(executionId);

        verify(executor, times(1)).resolveTarget(any(), anyString(), any());
        verify(executor, times(1)).restart(any(), anyString(), any());
    }

    /** 状态推进都是带期望状态与版本的条件更新：非 PENDING 不能再进入 RUNNING，过时版本不能落账结果（04 §82、DB 层防线）。 */
    @Test
    void stateChangesAreConditionalOnStatusAndVersion() {
        long executionId = approvedExecution();
        long version = executions.findById(executionId).orElseThrow().lockVersion();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        assertThat(executions.markSucceeded(executionId, version, "service.restart.result", 1, "{}", now))
                .as("PENDING cannot succeed")
                .isFalse();
        assertThat(executions.markRunning(executionId, version + 1, "{}", now))
                .as("stale version")
                .isFalse();
        String context = jdbc.queryForObject(
                "SELECT CAST(execution_context_payload AS CHAR) FROM action_execution WHERE id = ?",
                String.class,
                executionId);
        assertThat(executions.markRunning(executionId, version, context, now)).isTrue();
        assertThat(executions.markRunning(executionId, version + 1, context, now))
                .as("RUNNING cannot be admitted again")
                .isFalse();
        assertThat(executions.markFailed(
                        executionId, ActionExecutionStatus.PENDING, version + 1, "TIMEOUT", "timeout", now))
                .as("wrong source status")
                .isFalse();
        assertThat(executions.markFailed(executionId, ActionExecutionStatus.RUNNING, version, "TIMEOUT", "t", now))
                .as("stale version")
                .isFalse();
        assertThat(execution(executionId)).containsEntry("status", "RUNNING");
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

    private void restartAnswers(ServiceRestartExecutor.RestartOutcome outcome) {
        doAnswer(invocation -> {
                    transactionActiveDuringDocker.add(TransactionSynchronizationManager.isActualTransactionActive());
                    return outcome;
                })
                .when(executor)
                .restart(any(), anyString(), any());
    }

    private Map<String, Object> execution(long executionId) {
        return jdbc.queryForMap(
                "SELECT status, result_schema_name, error_code, IF(started_at IS NULL, 'NO', 'YES') AS has_started"
                        + " FROM action_execution WHERE id = ?",
                executionId);
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
