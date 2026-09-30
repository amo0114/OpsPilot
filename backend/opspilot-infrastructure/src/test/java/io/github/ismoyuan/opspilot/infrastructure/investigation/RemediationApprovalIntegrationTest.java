package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

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
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.investigation.ContinueInvestigationCommand;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.remediation.RemediationActions;
import io.github.ismoyuan.opspilot.application.remediation.RemediationApplicationService;
import io.github.ismoyuan.opspilot.application.remediation.RemediationDraftContextBuilder;
import io.github.ismoyuan.opspilot.application.remediation.RemediationProposalValidator;
import io.github.ismoyuan.opspilot.application.remediation.RequestRemediationCommand;
import io.github.ismoyuan.opspilot.application.remediation.RequestRemediationResult;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * 08 TASK-065～066：真实 MySQL 上的请求处理建议与审批决定。AI 为脚本替身（在无事务的线程状态下被调用，可在“AI 运行中”改变数据
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
    JdbcTemplate jdbc;

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

        assertThat(approvals.reject(decision(approvalId, 0, 8, "当前不希望重启消费者。"))).isEqualTo(rejected);
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
     * 05 §38、08 TASK-066 分阶段边界：批准复核 Plan 与动作；全部通过时因为尚无任何 RecoveryPolicy（TASK-074 起）如实返回
     * RECOVERY_POLICY_NOT_FOUND，Approval 保持 PENDING、Incident 保持 AWAITING_APPROVAL，不产生半套 APPROVED。
     */
    @Test
    void approvalIsRecheckedAndNeverHalfAppliedBeforeExecutionExists() {
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

    // ---------------------------------------------------------------- helpers

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
