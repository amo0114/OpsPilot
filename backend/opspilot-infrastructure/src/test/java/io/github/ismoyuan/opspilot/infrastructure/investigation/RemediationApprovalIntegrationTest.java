package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import io.github.ismoyuan.opspilot.application.approval.ApprovalDecisionResult;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWorkSource;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutionContextV1;
import io.github.ismoyuan.opspilot.application.incident.CancelIncidentCommand;
import io.github.ismoyuan.opspilot.application.incident.IncidentApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.ContinueInvestigationCommand;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyActivationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySelector;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySnapshotV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyValidator;
import io.github.ismoyuan.opspilot.application.remediation.RemediationActions;
import io.github.ismoyuan.opspilot.application.remediation.RemediationApplicationService;
import io.github.ismoyuan.opspilot.application.remediation.RemediationDraftContextBuilder;
import io.github.ismoyuan.opspilot.application.remediation.RemediationProposalValidator;
import io.github.ismoyuan.opspilot.application.remediation.RequestRemediationCommand;
import io.github.ismoyuan.opspilot.application.remediation.RequestRemediationResult;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
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
 * 08 TASK-065～067、069：真实 MySQL 上的请求处理建议、审批决定与批准创建 Execution。AI 为脚本替身（在无事务的线程状态下被调用，可在“AI 运行中”改变数据
 * 以制造复核失败）；其余为生产用例、仓储与约束。
 */
@SpringBootTest
@Testcontainers
@Import({
    RemediationApplicationService.class,
    RemediationDraftContextBuilder.class,
    RemediationActions.class,
    RemediationProposalValidator.class,
    ApprovalApplicationService.class,
    InvestigationApplicationService.class,
    IncidentApplicationService.class,
    RecoveryPolicySelector.class,
    RecoveryPolicyValidator.class,
    RecoveryPolicyActivationService.class,
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    ClockConfiguration.class
})
class RemediationApprovalIntegrationTest {

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
    InvestigationApplicationService investigations;

    @Autowired
    IncidentApplicationService incidentService;

    @Autowired
    RecoveryPolicyActivationService recoveryPolicies;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    SchemaCodecRegistry codecs;

    @Autowired
    List<DispatchableWorkSource> workSources;

    @MockitoBean
    AiDecisionPort ai;

    @MockitoBean
    WorkDispatcher dispatcher;

    @MockitoSpyBean
    TimelineRepository timeline;

    RemediationFixture seeded;
    final List<Boolean> transactionActiveDuringAi = new ArrayList<>();

    @BeforeEach
    void seed() {
        seeded = RemediationFixture.seed(jdbc);
        transactionActiveDuringAi.clear();
        proposeTarget(seeded.consumer(), request -> {});
    }

    // ---------------------------------------------------------------- TASK-065

    /**
     * 05 §30～§32、04 §77：事务外调用 AI，随后一个短事务创建 ACTIVE Plan（基于当前 Diagnosis）、Action（Java 的 MEDIUM 与需要审批、
     * 参数 {}）、PENDING Approval，Incident DIAGNOSED → AWAITING_APPROVAL，并写 REMEDIATION_PROPOSED 与 APPROVAL_REQUESTED。
     */
    @Test
    void aValidProposalCreatesThePlanActionAndPendingApprovalInOneCommit() {
        RequestRemediationResult result = request();

        assertThat(transactionActiveDuringAi).containsExactly(false);
        assertThat(result.status()).isEqualTo(IncidentStatus.AWAITING_APPROVAL);
        assertThat(result.version()).isEqualTo(8);
        assertThat(jdbc.queryForMap("SELECT p.status AS plan_status, p.diagnosis_id, ra.capability_key,"
                        + " CAST(ra.target_resource_id AS SIGNED) AS target, ra.risk_level, ra.requires_approval,"
                        + " CAST(ra.parameter_payload AS CHAR) AS payload, ra.parameter_schema_name,"
                        + " a.status AS approval_status, CAST(a.lock_version AS SIGNED) AS approval_version"
                        + " FROM remediation_plan p JOIN remediation_action ra ON ra.remediation_plan_id = p.id"
                        + " JOIN approval_request a ON a.remediation_action_id = ra.id"))
                .containsEntry("plan_status", "ACTIVE")
                .containsEntry("capability_key", "service.restart")
                .containsEntry("target", seeded.consumer())
                .containsEntry("risk_level", "MEDIUM")
                .containsEntry("requires_approval", true)
                .containsEntry("payload", "{}")
                .containsEntry("parameter_schema_name", "service.restart.request")
                .containsEntry("approval_status", "PENDING")
                .containsEntry("approval_version", 0L);
        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(event_type, '/', actor_type) FROM incident_timeline_event ORDER BY id",
                        String.class))
                .containsExactly("REMEDIATION_PROPOSED/AI_RUNTIME", "APPROVAL_REQUESTED/USER");
    }

    /** 05 §31：AI 不可用、选择 allowedActions 之外的目标——都不留下半套记录，Incident 保持 DIAGNOSED 与原版本。 */
    @Test
    void aiFailuresAndOutOfListProposalsWriteNothing() {
        doThrow(new ApplicationException(ErrorCode.AI_RUNTIME_UNAVAILABLE, "AI runtime is unavailable"))
                .when(ai)
                .draftRemediation(any());
        assertRequestRejected(ErrorCode.AI_RUNTIME_UNAVAILABLE);

        proposeTarget(seeded.projectApi(), request -> {});
        assertRequestRejected(ErrorCode.AI_INTENT_NOT_ALLOWED);
    }

    /**
     * 05 §30 第 3 步：AI 运行期间事实变化时，新的短事务复核拒绝——目标的 service.restart 绑定被停用（REMEDIATION_ACTION_NOT_EXECUTABLE）、
     * Incident 被他人更新（INCIDENT_VERSION_CONFLICT）；时间线写入失败使整次创建回滚。
     */
    @Test
    void changesDuringTheAiCallAndLaterFailuresLeaveNothingBehind() {
        proposeTarget(
                seeded.consumer(),
                request -> jdbc.update(
                        "UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ?",
                        seeded.consumer()));
        assertRequestRejected(ErrorCode.REMEDIATION_ACTION_NOT_EXECUTABLE);
        jdbc.update("UPDATE capability_binding SET enabled = TRUE");

        proposeTarget(
                seeded.consumer(),
                request -> jdbc.update(
                        "UPDATE incident SET lock_version = 8 WHERE id = ?",
                        seeded.investigation().incidentId()));
        assertThatThrownBy(this::request)
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCIDENT_VERSION_CONFLICT));
        jdbc.update(
                "UPDATE incident SET lock_version = 7 WHERE id = ?",
                seeded.investigation().incidentId());

        proposeTarget(seeded.consumer(), request -> {});
        doThrow(new IllegalStateException("timeline down")).when(timeline).append(any());
        assertThatThrownBy(this::request).isInstanceOf(IllegalStateException.class);
        assertNothingCreated();
    }

    // ---------------------------------------------------------------- TASK-066

    /** 05 §37：待审批内容具体到 Action 与目标资源。 */
    @Test
    void thePendingApprovalShowsTheConcreteAction() {
        long approvalId = request().approvalId();

        var view = approvals.getApproval(approvalId);

        assertThat(view.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(view.version()).isZero();
        assertThat(view.incidentKey()).isEqualTo(seeded.incidentKey());
        assertThat(view.capabilityKey()).isEqualTo("service.restart");
        assertThat(view.resourceKey()).isEqualTo("statistics-consumer");
        assertThat(view.riskLevel().name()).isEqualTo("MEDIUM");
        assertThat(view.decidedBy()).isNull();
        assertThatThrownBy(() -> approvals.getApproval(approvalId + 1000))
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 01 §24.2、05 §41、§43：拒绝记决定人与说明、版本加一，方案 CANCELLED，Incident 回到 DIAGNOSED 并写 APPROVAL_REJECTED；同一决定与
     * 说明的重复提交（即使版本已过时）返回原结果且不再写入；其他决定一律 APPROVAL_ALREADY_DECIDED。
     */
    @Test
    void rejectionIsRecordedOnceAndCannotBeReversed() {
        long approvalId = request().approvalId();

        ApprovalDecisionResult rejected = approvals.reject(decision(approvalId, 0, 8, "当前不希望重启消费者。"));

        assertThat(rejected.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(rejected.approvalVersion()).isOne();
        assertThat(rejected.incidentStatus()).isEqualTo(IncidentStatus.DIAGNOSED);
        assertThat(rejected.incidentVersion()).isEqualTo(9);
        assertThat(state(approvalId)).isEqualTo("REJECTED/demo-user/当前不希望重启消费者。/CANCELLED");
        assertThat(events("APPROVAL_REJECTED")).isOne();

        ApprovalDecisionResult repeated = approvals.reject(decision(approvalId, 0, 8, "当前不希望重启消费者。"));
        assertThat(repeated.replayed()).isTrue();
        assertThat(repeated)
                .usingRecursiveComparison()
                .ignoringFields("replayed")
                .isEqualTo(rejected);
        assertThat(events("APPROVAL_REJECTED")).isOne();
        for (Runnable other : List.<Runnable>of(
                () -> approvals.reject(decision(approvalId, 1, 9, "另一条说明")),
                () -> approvals.cancel(decision(approvalId, 1, 9, null)),
                () -> approvals.approve(decision(approvalId, 1, 9, null)))) {
            assertThatThrownBy(other::run)
                    .isInstanceOfSatisfying(
                            OpsPilotException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
        }
    }

    /** 05 §42：撤回后 Incident 回到 DIAGNOSED，不再有 PENDING Approval，可以继续调查。 */
    @Test
    void cancellingAnApprovalReturnsToDiagnosedAndAllowsContinuing() {
        long approvalId = request().approvalId();

        approvals.cancel(decision(approvalId, 0, 8, null));

        assertThat(state(approvalId)).isEqualTo("CANCELLED/demo-user/-/CANCELLED");
        assertThat(events("APPROVAL_CANCELLED")).isOne();
        investigations.continueInvestigation(new ContinueInvestigationCommand(seeded.incidentKey(), 9, "demo-user"));
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM incident WHERE id = ?",
                        String.class,
                        seeded.investigation().incidentId()))
                .isEqualTo("INVESTIGATING");
    }

    /** 05 §41：过时的 Approval / Incident 版本与超长说明在任何写入之前被拒绝。 */
    @Test
    void staleVersionsAndInvalidCommentsAreRejectedWithoutWrites() {
        long approvalId = request().approvalId();

        assertDecisionRejected(
                () -> approvals.reject(decision(approvalId, 1, 8, null)), ErrorCode.APPROVAL_VERSION_CONFLICT);
        assertDecisionRejected(
                () -> approvals.reject(decision(approvalId, 0, 7, null)), ErrorCode.INCIDENT_VERSION_CONFLICT);
        assertDecisionRejected(
                () -> approvals.cancel(decision(approvalId, 0, 8, "长".repeat(501))),
                ErrorCode.REQUEST_VALIDATION_FAILED);
        assertThat(state(approvalId)).isEqualTo("PENDING/-/-/ACTIVE");
    }

    /**
     * 05 §38：批准复核 Plan、动作与版本；目标没有恢复策略时 RECOVERY_POLICY_NOT_FOUND。任何拒绝都不写入：Approval 保持 PENDING、
     * Incident 保持 AWAITING_APPROVAL，不产生半套 APPROVED。
     */
    @Test
    void approvalIsRecheckedAndNeverHalfApplied() {
        long approvalId = request().approvalId();

        assertDecisionRejected(
                () -> approvals.approve(decision(approvalId, 0, 8, "批准执行")), ErrorCode.RECOVERY_POLICY_NOT_FOUND);
        jdbc.update("UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ?", seeded.consumer());
        assertDecisionRejected(
                () -> approvals.approve(decision(approvalId, 0, 8, null)), ErrorCode.REMEDIATION_ACTION_NOT_EXECUTABLE);
        jdbc.update("UPDATE remediation_plan SET status = 'SUPERSEDED'");
        assertDecisionRejected(
                () -> approvals.approve(decision(approvalId, 0, 8, null)), ErrorCode.REMEDIATION_PLAN_SUPERSEDED);
        jdbc.update("UPDATE remediation_plan SET status = 'ACTIVE'");
        assertDecisionRejected(
                () -> approvals.approve(decision(approvalId, 0, 7, null)), ErrorCode.INCIDENT_VERSION_CONFLICT);

        assertThat(state(approvalId)).isEqualTo("PENDING/-/-/ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM incident WHERE id = ?",
                        String.class,
                        seeded.investigation().incidentId()))
                .isEqualTo("AWAITING_APPROVAL");
    }

    /** 并发的拒绝与撤回在 Incident → Approval 锁序上串行：只有一个决定生效，另一个得到已决定。 */
    @Test
    void concurrentDecisionsAdmitExactlyOne() throws Exception {
        long approvalId = request().approvalId();
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Object>> outcomes = new ArrayList<>();
        for (Runnable decisionCall : List.<Runnable>of(
                () -> approvals.reject(decision(approvalId, 0, 8, null)),
                () -> approvals.cancel(decision(approvalId, 0, 8, null)))) {
            outcomes.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    decisionCall.run();
                    return "OK";
                } catch (OpsPilotException ex) {
                    return ex.errorCode();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (var outcome : outcomes) {
            results.add(outcome.get(30, TimeUnit.SECONDS));
        }

        assertThat(results).containsOnlyOnce("OK");
        assertThat(results)
                .filteredOn(ErrorCode.class::isInstance)
                .singleElement()
                .isIn(ErrorCode.APPROVAL_ALREADY_DECIDED, ErrorCode.APPROVAL_VERSION_CONFLICT);
        assertThat(events("APPROVAL_REJECTED") + events("APPROVAL_CANCELLED")).isOne();
    }

    // ---------------------------------------------------------------- TASK-067

    /**
     * 04 §52、§78、08 TASK-067：批准在任何写入前选择目标资源唯一合法的 ACTIVE RecoveryPolicy——没有、按此刻绑定不合法、多条
     * 各有其码，均不写入。
     */
    @Test
    void approvalSelectsTheSingleLegalRecoveryPolicyBeforeAnyWrite() {
        long approvalId = request().approvalId();

        assertRejectedWith(
                () -> approvals.approve(decision(approvalId, 0, 8, null)),
                ErrorCode.RECOVERY_POLICY_NOT_FOUND,
                Map.of("reason", "NO_ACTIVE_POLICY"));

        activateConsumerPolicy("consumer-recovery");
        jdbc.update(
                "UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ?"
                        + " AND capability_key = 'service.inspect'",
                seeded.consumer());
        assertRejectedWith(
                () -> approvals.approve(decision(approvalId, 0, 8, null)),
                ErrorCode.RECOVERY_POLICY_NOT_FOUND,
                Map.of(
                        "reason", "POLICY_NOT_EXECUTABLE",
                        "check", "CAPABILITY_NOT_BOUND",
                        "checkReason", "BINDING_MISSING_OR_DISABLED",
                        "criterionKey", "consumer-running"));
        jdbc.update(
                "UPDATE capability_binding SET enabled = TRUE WHERE managed_resource_id = ?"
                        + " AND capability_key = 'service.inspect'",
                seeded.consumer());

        // 绕过激活服务制造的第二条 ACTIVE：不猜测选择（04 §49）
        jdbc.update(
                "INSERT INTO recovery_policy (managed_resource_id, policy_key, name, version_no, criteria_schema_name,"
                        + " criteria_schema_version, criteria_payload, status, created_at, activated_at)"
                        + " SELECT managed_resource_id, 'duplicate', name, 1, criteria_schema_name,"
                        + " criteria_schema_version, criteria_payload, 'ACTIVE', created_at, activated_at"
                        + " FROM recovery_policy WHERE status = 'ACTIVE'");
        assertRejectedWith(
                () -> approvals.approve(decision(approvalId, 0, 8, null)),
                ErrorCode.RECOVERY_POLICY_AMBIGUOUS,
                Map.of("activeCount", 2));

        assertNothingDecided(approvalId);
    }

    /**
     * B21-R1：批准在等待 Incident 行锁期间提交的配置变化必须被复核看到。另一连接持有 Incident 行锁；批准开始并阻塞后，停用策略判据
     * 所需的 service.inspect 绑定并提交，再释放锁——复核必须读到停用（锁前建立的快照会误判策略仍合法）。
     */
    @Test
    void approvalRechecksDataCommittedWhileWaitingForTheIncidentLock() throws Exception {
        long approvalId = request().approvalId();
        activateConsumerPolicy("consumer-recovery");

        CompletableFuture<Object> approval;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT id FROM incident WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, seeded.investigation().incidentId());
                lock.executeQuery().close();
            }
            approval = CompletableFuture.supplyAsync(
                    () -> outcome(() -> approvals.approve(decision(approvalId, 0, 8, null))));
            awaitLockWait();
            jdbc.update(
                    "UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ?"
                            + " AND capability_key = 'service.inspect'",
                    seeded.consumer());
            holder.commit();
        }

        assertThat(approval.get(30, TimeUnit.SECONDS)).isEqualTo(ErrorCode.RECOVERY_POLICY_NOT_FOUND);
        assertNothingDecided(approvalId);
    }

    /** 批准与拒绝并发：锁序 Incident → Approval 使它们串行，只有先取得锁的决定落账，另一个看到已决定或版本冲突。 */
    @Test
    void concurrentApproveAndRejectDecideExactlyOnce() throws Exception {
        long approvalId = request().approvalId();
        activateConsumerPolicy("consumer-recovery");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Object> approve = CompletableFuture.supplyAsync(() -> {
            await(start);
            return outcome(() -> approvals.approve(decision(approvalId, 0, 8, null)));
        });
        CompletableFuture<Object> reject = CompletableFuture.supplyAsync(() -> {
            await(start);
            return outcome(() -> approvals.reject(decision(approvalId, 0, 8, null)));
        });
        start.countDown();

        List<Object> outcomes = List.of(approve.get(30, TimeUnit.SECONDS), reject.get(30, TimeUnit.SECONDS));

        assertThat(outcomes).containsOnlyOnce("OK");
        assertThat(outcomes)
                .filteredOn(ErrorCode.class::isInstance)
                .singleElement()
                .isIn(ErrorCode.APPROVAL_ALREADY_DECIDED, ErrorCode.APPROVAL_VERSION_CONFLICT);
        int executions = jdbc.queryForObject("SELECT COUNT(*) FROM action_execution", Integer.class);
        if (outcomes.getFirst().equals("OK")) {
            assertThat(state(approvalId)).isEqualTo("APPROVED/demo-user/-/ACTIVE");
            assertThat(executions).isOne();
        } else {
            assertThat(state(approvalId)).isEqualTo("REJECTED/demo-user/-/CANCELLED");
            assertThat(executions).isZero();
        }
        assertThat(events("APPROVAL_APPROVED") + events("APPROVAL_REJECTED")).isOne();
    }

    // ---------------------------------------------------------------- TASK-069

    /**
     * 04 §45、§52、§78、05 §38～§39：复核全部通过后一个事务落账——Approval APPROVED（决定人、说明、版本 1）、PENDING Execution
     * （确定性幂等键、执行器、策略 id/版本、完整快照、受信执行上下文、核对上限快照）、Incident EXECUTING（版本 9）与 APPROVAL_APPROVED；
     * 方案仍 ACTIVE（执行结果落账时才 EXECUTED）。提交之后才派发。
     */
    @Test
    void approvalFreezesTheContractCreatesThePendingExecutionAndDispatchesAfterCommit() {
        long approvalId = request().approvalId();
        long policyId = activateConsumerPolicy("consumer-recovery");
        List<String> dispatchObservations = new ArrayList<>();
        // 派发时从另一条独立连接读取：只有批准事务已提交，才能看到 PENDING Execution
        doAnswer(invocation -> {
                    long executionId = invocation.getArgument(0);
                    try (Connection other = DriverManager.getConnection(
                                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                            var query = other.prepareStatement("SELECT status FROM action_execution WHERE id = ?")) {
                        query.setLong(1, executionId);
                        try (var rs = query.executeQuery()) {
                            dispatchObservations.add(rs.next() ? rs.getString(1) : "NOT_VISIBLE");
                        }
                    }
                    return null;
                })
                .when(dispatcher)
                .dispatchActionExecution(anyLong());

        ApprovalDecisionResult result = approvals.approve(decision(approvalId, 0, 8, "批准执行"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(result.approvalVersion()).isOne();
        assertThat(result.incidentStatus()).isEqualTo(IncidentStatus.EXECUTING);
        assertThat(result.incidentVersion()).isEqualTo(9);
        assertThat(result.execution().status()).isEqualTo(ActionExecutionStatus.PENDING);
        long executionId = result.execution().executionId();
        assertThat(dispatchObservations).containsExactly("PENDING");
        assertThat(state(approvalId)).isEqualTo("APPROVED/demo-user/批准执行/ACTIVE");
        long actionId = jdbc.queryForObject(
                "SELECT remediation_action_id FROM approval_request WHERE id = ?", Long.class, approvalId);
        assertThat(jdbc.queryForMap(
                        "SELECT status, idempotency_key, executor_key, CAST(recovery_policy_id AS SIGNED) AS policy,"
                                + " recovery_policy_version, execution_context_schema_name,"
                                + " execution_context_schema_version, reconciliation_attempt_count,"
                                + " max_reconciliation_attempts, started_at, CAST(approval_request_id AS SIGNED) AS"
                                + " approval FROM action_execution WHERE id = ?",
                        executionId))
                .containsEntry("status", "PENDING")
                .containsEntry("idempotency_key", "action-execution:" + actionId)
                .containsEntry("executor_key", "docker.service-restart")
                .containsEntry("policy", policyId)
                .containsEntry("recovery_policy_version", 1L)
                .containsEntry("execution_context_schema_name", "service.restart.execution-context")
                .containsEntry("execution_context_schema_version", 1L)
                .containsEntry("reconciliation_attempt_count", 0L)
                .containsEntry("max_reconciliation_attempts", 3L)
                .containsEntry("started_at", null)
                .containsEntry("approval", approvalId);

        RecoveryPolicySnapshotV1 snapshot = codecs.decode(
                RecoveryPolicySnapshotV1.SCHEMA_NAME,
                1,
                jdbc.queryForObject(
                        "SELECT CAST(recovery_policy_snapshot AS CHAR) FROM action_execution WHERE id = ?",
                        String.class,
                        executionId),
                RecoveryPolicySnapshotV1.class);
        assertThat(snapshot.policyId()).isEqualTo(policyId);
        assertThat(snapshot.policyKey()).isEqualTo("consumer-recovery");
        assertThat(snapshot.policyVersion()).isOne();
        assertThat(snapshot.managedResourceId()).isEqualTo(seeded.consumer());
        assertThat(snapshot.maxDurationSeconds()).isEqualTo(60);
        assertThat(snapshot.criteria())
                .extracting(
                        criterion -> criterion.criterion().criterionKey(),
                        RecoveryPolicySnapshotV1.SnapshotCriterion::targetResourceId,
                        RecoveryPolicySnapshotV1.SnapshotCriterion::consumerGroup)
                .containsExactly(
                        tuple("stream-lag-drained", seeded.investigation().streamId(), "stats-consumer-group"),
                        tuple("consumer-running", seeded.consumer(), null));

        ServiceRestartExecutionContextV1 context = codecs.decode(
                ServiceRestartExecutionContextV1.SCHEMA_NAME,
                1,
                jdbc.queryForObject(
                        "SELECT CAST(execution_context_payload AS CHAR) FROM action_execution WHERE id = ?",
                        String.class,
                        executionId),
                ServiceRestartExecutionContextV1.class);
        assertThat(context)
                .isEqualTo(new ServiceRestartExecutionContextV1(
                        seeded.consumer(),
                        jdbc.queryForObject(
                                "SELECT id FROM resource_binding WHERE managed_resource_id = ?",
                                Long.class,
                                seeded.consumer()),
                        jdbc.queryForObject(
                                "SELECT id FROM data_source_connection WHERE connection_key = 'docker-local'",
                                Long.class),
                        "shortlink-statistics-consumer",
                        null));
        assertThat(jdbc.queryForMap(
                        "SELECT status, CAST(lock_version AS SIGNED) AS version FROM incident WHERE id = ?",
                        seeded.investigation().incidentId()))
                .containsEntry("status", "EXECUTING")
                .containsEntry("version", 9L);
        assertThat(jdbc.queryForObject(
                        "SELECT CAST(payload->>'$.executionId' AS SIGNED) FROM incident_timeline_event"
                                + " WHERE event_type = 'APPROVAL_APPROVED'",
                        Long.class))
                .isEqualTo(executionId);
    }

    /** 04 §78：时间线写入失败使整个批准回滚——没有 APPROVED、没有 Execution、Incident 不变，也不派发。 */
    @Test
    void aFailureInTheApprovalTransactionLeavesNothingBehind() {
        long approvalId = request().approvalId();
        activateConsumerPolicy("consumer-recovery");
        doThrow(new IllegalStateException("timeline unavailable"))
                .when(timeline)
                .append(argThat(event -> event.eventType() == TimelineEventType.APPROVAL_APPROVED));

        assertThatThrownBy(() -> approvals.approve(decision(approvalId, 0, 8, null)))
                .isInstanceOf(IllegalStateException.class);

        assertNothingDecided(approvalId);
        verify(dispatcher, never()).dispatchActionExecution(anyLong());
    }

    /**
     * 05 §43、04 §46～§47：同一操作者重复提交相同批准与说明（含过时版本）返回原决定与既有 Execution，不再创建、不再派发、不写时间线；
     * 说明不同或改为拒绝均为已决定。
     */
    @Test
    void repeatingTheSameApprovalReturnsTheExistingExecution() {
        long approvalId = request().approvalId();
        activateConsumerPolicy("consumer-recovery");
        ApprovalDecisionResult first = approvals.approve(decision(approvalId, 0, 8, "批准执行"));

        ApprovalDecisionResult repeated = approvals.approve(decision(approvalId, 0, 8, "  批准执行 "));

        assertThat(repeated.replayed()).isTrue();
        assertThat(repeated.execution()).isEqualTo(first.execution());
        assertThat(repeated.approvalVersion()).isOne();
        assertThat(repeated.incidentStatus()).isEqualTo(IncidentStatus.EXECUTING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM action_execution", Integer.class))
                .isOne();
        assertThat(events("APPROVAL_APPROVED")).isOne();
        verify(dispatcher, times(1)).dispatchActionExecution(first.execution().executionId());
        for (Runnable other : List.<Runnable>of(
                () -> approvals.approve(decision(approvalId, 1, 9, "另一条说明")),
                () -> approvals.reject(decision(approvalId, 1, 9, "批准执行")))) {
            assertDecisionRejected(other, ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
    }

    /** 并发的相同批准：锁序使它们串行，只创建一个 Execution、只派发一次；后到者得到原决定。 */
    @Test
    void concurrentApprovalsCreateExactlyOneExecution() throws Exception {
        long approvalId = request().approvalId();
        activateConsumerPolicy("consumer-recovery");
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<ApprovalDecisionResult>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                await(start);
                return approvals.approve(decision(approvalId, 0, 8, null));
            }));
        }
        start.countDown();
        List<ApprovalDecisionResult> results = new ArrayList<>();
        for (var call : calls) {
            results.add(call.get(30, TimeUnit.SECONDS));
        }

        assertThat(results).extracting(ApprovalDecisionResult::replayed).containsExactlyInAnyOrder(false, true);
        assertThat(results)
                .extracting(ApprovalDecisionResult::execution)
                .containsOnly(results.getFirst().execution());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM action_execution", Integer.class))
                .isOne();
        verify(dispatcher, times(1)).dispatchActionExecution(anyLong());
    }

    /**
     * 08 TASK-069：提交后派发失败不影响已提交的批准；PENDING Execution 由补派发来源重新找到（07 §51），RUNNING 不在其中。
     */
    @Test
    void aFailedDispatchIsRecoveredFromThePendingExecutionSource() {
        long approvalId = request().approvalId();
        activateConsumerPolicy("consumer-recovery");
        doThrow(new IllegalStateException("queue full")).when(dispatcher).dispatchActionExecution(anyLong());

        long executionId =
                approvals.approve(decision(approvalId, 0, 8, null)).execution().executionId();

        assertThat(state(approvalId)).startsWith("APPROVED/");
        assertThat(pendingExecutionWork()).containsExactly(new DispatchableWork.ActionExecution(executionId));
        jdbc.update(
                "UPDATE action_execution SET status = 'RUNNING', started_at = UTC_TIMESTAMP(3) WHERE id = ?",
                executionId);
        assertThat(pendingExecutionWork()).isEmpty();
    }

    /**
     * 01 §25、05 §33（回填 TASK-019）：真实 request-remediation 产生的方案在等待审批时取消 Incident，同事务撤回 PENDING Approval 与
     * ACTIVE Plan，之后该 Approval 不能再作出任何决定。
     */
    @Test
    void cancellingAnIncidentAwaitingApprovalWithdrawsTheRealPlan() {
        long approvalId = request().approvalId();

        incidentService.cancelIncident(new CancelIncidentCommand(seeded.incidentKey(), 8, "确认误报。", "demo-user"));

        assertThat(state(approvalId)).isEqualTo("CANCELLED/demo-user/-/CANCELLED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM incident WHERE id = ?",
                        String.class,
                        seeded.investigation().incidentId()))
                .isEqualTo("CANCELLED");
        for (Runnable decisionCall : List.<Runnable>of(
                () -> approvals.approve(decision(approvalId, 1, 9, null)),
                () -> approvals.reject(decision(approvalId, 1, 9, null)))) {
            assertDecisionRejected(decisionCall, ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
    }

    // ---------------------------------------------------------------- helpers

    private long activateConsumerPolicy(String policyKey) {
        return seeded.activateRecoveryPolicy(recoveryPolicies, policyKey);
    }

    private List<DispatchableWork> pendingExecutionWork() {
        return workSources.stream()
                .<DispatchableWork>flatMap(source -> source.findDispatchable().stream())
                .filter(DispatchableWork.ActionExecution.class::isInstance)
                .toList();
    }

    /** 以 root 查询 InnoDB 事务，直到有事务处于锁等待。 */
    private static void awaitLockWait() throws Exception {
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                var query = root.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'")) {
            for (int i = 0; i < 200; i++) {
                try (var rs = query.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("approval never waited for the incident lock");
    }

    private static Object outcome(Runnable call) {
        try {
            call.run();
            return "OK";
        } catch (OpsPilotException ex) {
            return ex.errorCode();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void assertRejectedWith(Runnable call, ErrorCode code, Map<String, Object> details) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OpsPilotException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(code);
            assertThat(ex.details()).containsAllEntriesOf(details);
        });
    }

    /** Approval 仍 PENDING、方案仍 ACTIVE、Incident 仍 AWAITING_APPROVAL（版本 8），没有 Execution。 */
    private void assertNothingDecided(long approvalId) {
        assertThat(state(approvalId)).isEqualTo("PENDING/-/-/ACTIVE");
        assertThat(jdbc.queryForMap(
                        "SELECT status, CAST(lock_version AS SIGNED) AS version FROM incident WHERE id = ?",
                        seeded.investigation().incidentId()))
                .containsEntry("status", "AWAITING_APPROVAL")
                .containsEntry("version", 8L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM action_execution", Integer.class))
                .isZero();
    }

    private RequestRemediationResult request() {
        return remediations.requestRemediation(new RequestRemediationCommand(seeded.incidentKey(), 7, "demo-user"));
    }

    /** AI 替身：记录调用时是否处于事务中、执行 {@code duringCall}，然后选择 {@code target}。 */
    private void proposeTarget(long target, Consumer<RemediationDraftRequest> duringCall) {
        doAnswer(invocation -> {
                    RemediationDraftRequest request = invocation.getArgument(0);
                    transactionActiveDuringAi.add(TransactionSynchronizationManager.isActualTransactionActive());
                    duringCall.accept(request);
                    return new RemediationDraftResponse(
                            1,
                            request.correlationId(),
                            RemediationDraftResponse.RemediationIntentType.PROPOSE_REMEDIATION,
                            new RemediationDraftResponse.Proposal(
                                    "恢复统计消费",
                                    "重新启动已停止的统计消费者。",
                                    new RemediationDraftResponse.Action(
                                            "service.restart",
                                            target,
                                            new ServiceRestartParametersV1(),
                                            "重新启动统计消费者",
                                            "统计消费短暂中断后恢复。")));
                })
                .when(ai)
                .draftRemediation(any());
    }

    private void assertRequestRejected(ErrorCode code) {
        assertThatThrownBy(this::request)
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(code));
        assertNothingCreated();
    }

    private void assertNothingCreated() {
        for (String table : List.of("remediation_plan", "remediation_action", "approval_request")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                    .as(table)
                    .isZero();
        }
        Map<String, Object> incident = jdbc.queryForMap(
                "SELECT status, CAST(lock_version AS SIGNED) AS version FROM incident WHERE id = ?",
                seeded.investigation().incidentId());
        assertThat(incident).containsEntry("status", "DIAGNOSED").containsEntry("version", 7L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_timeline_event", Integer.class))
                .isZero();
    }

    private static void assertDecisionRejected(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(code));
    }

    private static ApprovalDecisionCommand decision(
            long approvalId, long approvalVersion, long incidentVersion, String comment) {
        return new ApprovalDecisionCommand(approvalId, approvalVersion, incidentVersion, comment, "demo-user");
    }

    /** Approval 状态 / 决定人 / 说明 / 方案状态（空值为 -）。 */
    private String state(long approvalId) {
        return jdbc.queryForObject(
                "SELECT CONCAT(a.status, '/', COALESCE(a.decided_by, '-'), '/', COALESCE(a.comment, '-'), '/', p.status)"
                        + " FROM approval_request a JOIN remediation_action ra ON ra.id = a.remediation_action_id"
                        + " JOIN remediation_plan p ON p.id = ra.remediation_plan_id WHERE a.id = ?",
                String.class,
                approvalId);
    }

    private int events(String type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_timeline_event WHERE event_type = ?", Integer.class, type);
    }
}
