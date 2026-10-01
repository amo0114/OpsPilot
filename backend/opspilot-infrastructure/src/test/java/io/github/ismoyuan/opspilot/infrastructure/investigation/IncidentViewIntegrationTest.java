package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.incident.query.AvailableActionsResolver;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentDetailView;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentQueryRepository;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentQueryService;
import io.github.ismoyuan.opspilot.application.investigation.query.InvestigationQueryService;
import io.github.ismoyuan.opspilot.application.recovery.CriterionReason;
import io.github.ismoyuan.opspilot.application.recovery.CriterionResult;
import io.github.ismoyuan.opspilot.application.recovery.ProjectedValue;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryOutcome;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyActivationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySelector;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySnapshotV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyValidator;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySample;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySampleReader;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryStatusReader;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryStatusView;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationResultV1;
import io.github.ismoyuan.opspilot.application.remediation.RemediationActions;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.timeline.query.TimelineEventView;
import io.github.ismoyuan.opspilot.application.timeline.query.TimelinePage;
import io.github.ismoyuan.opspilot.application.timeline.query.TimelineQueryService;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.execution.ActionExecutionStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentAction;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import io.github.ismoyuan.opspilot.domain.remediation.ApprovalStatus;
import io.github.ismoyuan.opspilot.domain.remediation.RemediationPlanStatus;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 08 TASK-084～086：真实 MySQL 上的 Timeline 游标、IncidentDetailView 各节与 availableActions。数据按各阶段由真实提交写入
 * （查询路径只读）；恢复策略经正式激活服务启用、快照由正式选择器生成，样本结果经正式 Codec 编码。
 */
@SpringBootTest
@Testcontainers
@Import({
    IncidentQueryService.class,
    InvestigationQueryService.class,
    AvailableActionsResolver.class,
    RecoveryStatusReader.class,
    RecoverySampleReader.class,
    TimelineQueryService.class,
    RemediationActions.class,
    RecoveryPolicySelector.class,
    RecoveryPolicyValidator.class,
    RecoveryPolicyActivationService.class,
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    ClockConfiguration.class
})
class IncidentViewIntegrationTest {

    static final String GROUP = "stats-consumer-group";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    IncidentQueryService incidents;

    @Autowired
    TimelineQueryService timeline;

    @Autowired
    RecoveryPolicyActivationService recoveryPolicies;

    @Autowired
    RecoveryPolicySelector selector;

    @Autowired
    ManagedResourceRepository resources;

    @Autowired
    SchemaCodecRegistry codecs;

    @Autowired
    JdbcTemplate jdbc;

    /** 一致性用例在第一次读取之后插入并发提交；其余用例不改变行为。 */
    @MockitoSpyBean
    IncidentQueryRepository queryRepository;

    RemediationFixture seeded;

    String key;

    @BeforeEach
    void seed() {
        seeded = RemediationFixture.seed(jdbc);
        key = seeded.incidentKey();
    }

    // ---------------------------------------------------------------- TASK-084 Timeline

    /**
     * 只读同一 Incident 的已提交事件，按 id 升序、afterId 之后、最多 limit 条；nextAfterId 是本段最后一条，读完后为空段并原样返回游标；
     * 与另一 Incident 交错写入的事件不混入。
     */
    @Test
    void timelineIsReadByCursorWithinOneIncident() {
        long a1 = event(incidentId(), "INCIDENT_CREATED", "创建故障：T");
        event(otherIncidentId(), "INCIDENT_CREATED", "其他故障");
        long a2 = event(incidentId(), "INVESTIGATION_STARTED", "开始调查");
        long a3 = event(incidentId(), "DIAGNOSIS_CREATED", "得出诊断");

        TimelinePage first = timeline.listEvents(key, 0, 2);
        assertThat(first.events()).extracting(TimelineEventView::id).containsExactly(a1, a2);
        assertThat(first.events().get(0).eventType()).isEqualTo(TimelineEventType.INCIDENT_CREATED);
        assertThat(first.events().get(0).summary()).isEqualTo("创建故障：T");
        assertThat(first.events().get(0).occurredAt()).isNotNull();
        assertThat(first.nextAfterId()).isEqualTo(a2);

        TimelinePage second = timeline.listEvents(key, first.nextAfterId(), 2);
        assertThat(second.events()).extracting(TimelineEventView::id).containsExactly(a3);
        assertThat(second.nextAfterId()).isEqualTo(a3);

        TimelinePage drained = timeline.listEvents(key, a3, 2);
        assertThat(drained.events()).isEmpty();
        assertThat(drained.nextAfterId()).isEqualTo(a3);

        event(incidentId(), "DIAGNOSIS_CREATED", "新诊断");
        assertThat(timeline.listEvents(key, a3, 50).events())
                .extracting(TimelineEventView::summary)
                .containsExactly("新诊断");
    }

    @Test
    void timelineOfAnUnknownIncidentIsNotFound() {
        for (String unknown : List.of("INC-20990101-0001", key.toLowerCase(), key + " ")) {
            assertThatThrownBy(() -> timeline.listEvents(unknown, 0, 10))
                    .isInstanceOfSatisfying(
                            ApplicationException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCIDENT_NOT_FOUND));
        }
    }

    // ---------------------------------------------------------------- TASK-085 详情各节

    /**
     * 已诊断：当前判断取最新 Diagnosis，why 只含其冻结的 SUPPORTS 证据（CONTEXT 不列入）；调查节区分本轮额度与历史累计；没有方案与
     * 验证时为空；lastTimelineEventId 为该 Incident 的最大事件 id（不受其他 Incident 影响）。
     */
    @Test
    void diagnosedIncidentShowsTheCurrentAssessmentAndItsFrozenSupport() {
        long last = event(incidentId(), "DIAGNOSIS_CREATED", "得出诊断");
        event(otherIncidentId(), "INCIDENT_CREATED", "其他故障");
        jdbc.update(
                "UPDATE investigation SET current_run_capability_count = 5, capability_call_count = 9 WHERE id = ?",
                seeded.investigation().investigationId());

        IncidentDetailView view = incidents.getIncident(key);

        assertThat(view.status()).isEqualTo(IncidentStatus.DIAGNOSED);
        assertThat(view.version()).isEqualTo(7);
        assertThat(view.lastTimelineEventId()).isEqualTo(last);
        assertThat(view.currentAssessment().diagnosisVersion()).isEqualTo(1);
        assertThat(view.currentAssessment().runNo()).isEqualTo(1);
        assertThat(view.currentAssessment().conclusionType())
                .isEqualTo(DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED);
        assertThat(view.currentAssessment().summary()).isEqualTo("统计消费者已停止运行，统计消息不再被消费。");
        assertThat(view.currentAssessment().why()).containsExactly("服务运行状态 STOPPED，退出码 137");
        assertThat(view.currentAssessment().terminationReason()).isEqualTo(TerminationReason.AGENT_COMPLETED);
        assertThat(view.investigation().runNo()).isEqualTo(1);
        assertThat(view.investigation().stopRequested()).isFalse();
        assertThat(view.investigation().budget().capabilityCallsUsed()).isEqualTo(5);
        assertThat(view.investigation().budget().capabilityCallsLimit()).isEqualTo(12);
        assertThat(view.investigation().budget().remainingCapabilityCalls()).isEqualTo(7);
        assertThat(view.investigation().totalCapabilityCalls()).isEqualTo(9);
        assertThat(view.remediation()).isNull();
        assertThat(view.recovery()).isNull();
    }

    /** 最新版本优先；UNDETERMINED 没有 SUPPORTS 时 why 为空，并给出收束原因。 */
    @Test
    void theLatestDiagnosisVersionIsTheCurrentAssessment() {
        jdbc.update(
                "INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, summary, impact_summary,"
                        + " termination_reason, created_at) VALUES (?, 1, 2, 'UNDETERMINED', '暂时无法确定原因', 'I',"
                        + " 'USER_STOPPED', UTC_TIMESTAMP(3))",
                seeded.investigation().investigationId());

        IncidentDetailView view = incidents.getIncident(key);

        assertThat(view.currentAssessment().diagnosisVersion()).isEqualTo(2);
        assertThat(view.currentAssessment().conclusionType()).isEqualTo(DiagnosisConclusionType.UNDETERMINED);
        assertThat(view.currentAssessment().why()).isEmpty();
        assertThat(view.currentAssessment().terminationReason()).isEqualTo(TerminationReason.USER_STOPPED);
    }

    /** 尚未开始调查：没有调查、判断、方案与验证，不返回占位值。 */
    @Test
    void aCreatedIncidentHasNoInvestigationFacts() {
        String created = createdIncident();

        IncidentDetailView view = incidents.getIncident(created);

        assertThat(view.status()).isEqualTo(IncidentStatus.CREATED);
        assertThat(view.investigation()).isNull();
        assertThat(view.currentAssessment()).isNull();
        assertThat(view.remediation()).isNull();
        assertThat(view.recovery()).isNull();
        assertThat(view.lastTimelineEventId()).isZero();
        assertThat(view.availableActions())
                .containsExactly(IncidentAction.START_INVESTIGATION, IncidentAction.CANCEL_INCIDENT);
    }

    /** 等待审批：最新方案及其 Action、PENDING Approval；尚无 Execution。 */
    @Test
    void awaitingApprovalShowsThePendingRemediation() {
        long approval = remediation("PENDING");
        jdbc.update("UPDATE incident SET status = 'AWAITING_APPROVAL' WHERE id = ?", incidentId());

        IncidentDetailView view = incidents.getIncident(key);

        assertThat(view.remediation().plan().status()).isEqualTo(RemediationPlanStatus.ACTIVE);
        assertThat(view.remediation().plan().title()).isEqualTo("恢复统计消费");
        assertThat(view.remediation().action().capabilityKey()).isEqualTo("service.restart");
        assertThat(view.remediation().action().targetResourceKey()).isEqualTo("statistics-consumer");
        assertThat(view.remediation().action().targetResourceName()).isEqualTo("统计消费者");
        assertThat(view.remediation().action().requiresApproval()).isTrue();
        assertThat(view.remediation().approval().approvalId()).isEqualTo(approval);
        assertThat(view.remediation().approval().status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(view.remediation().approval().decidedAt()).isNull();
        assertThat(view.remediation().execution()).isNull();
        assertThat(view.availableActions()).containsExactly(IncidentAction.CANCEL_INCIDENT);
    }

    /** 执行之后：方案 EXECUTED、Approval APPROVED，Execution 的状态与错误码如实给出（执行成功不等于已恢复）。 */
    @Test
    void anExecutedRemediationShowsItsExecution() {
        long approval = remediation("APPROVED");
        long action = jdbc.queryForObject(
                "SELECT remediation_action_id FROM approval_request WHERE id = ?", Long.class, approval);
        jdbc.update("UPDATE remediation_plan SET status = 'EXECUTED'");
        jdbc.update(
                "INSERT INTO action_execution (remediation_action_id, approval_request_id, idempotency_key, status,"
                        + " executor_key, recovery_policy_id, recovery_policy_version, recovery_policy_snapshot,"
                        + " execution_context_schema_name, execution_context_schema_version, execution_context_payload,"
                        + " error_code, error_message, max_reconciliation_attempts, started_at, finished_at, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'FAILED', 'docker', ?, 1, ?, 'service.restart.context', 1,"
                        + " '{}', 'EXECUTION_RESULT_UNCERTAIN', 'uncertain', 3, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                action,
                approval,
                "action-execution:" + action,
                policy(),
                codecs.encode(RecoveryPolicySnapshotV1.SCHEMA_NAME, 1, snapshot()));

        IncidentDetailView view = incidents.getIncident(key);

        assertThat(view.remediation().plan().status()).isEqualTo(RemediationPlanStatus.EXECUTED);
        assertThat(view.remediation().approval().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(view.remediation().approval().decidedAt()).isNotNull();
        assertThat(view.remediation().execution().status()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(view.remediation().execution().errorCode()).isEqualTo("EXECUTION_RESULT_UNCERTAIN");
    }

    /**
     * 验证进行中：按冻结快照顺序列出全部检查，尚无结论；已登记样本带真实投影值（积压按配置的消费组取值）或失败错误码，尚未采样的检查
     * 样本为空，不补造。
     */
    @Test
    void aRunningVerificationListsEveryCheckWithoutAConclusion() {
        long verification = verification("RUNNING");
        successfulLagSample(verification, 1, 2180L);
        jdbc.update("UPDATE incident SET status = 'VERIFYING' WHERE id = ?", incidentId());

        RecoveryStatusView recovery = incidents.getIncident(key).recovery();

        assertThat(recovery.verificationNo()).isEqualTo(1);
        assertThat(recovery.status()).isEqualTo(RecoveryVerificationStatus.RUNNING);
        assertThat(recovery.resultSummary()).isNull();
        assertThat(recovery.resourceKey()).isEqualTo("statistics-consumer");
        assertThat(recovery.policyKey()).isEqualTo("consumer-recovery");
        assertThat(recovery.policyVersion()).isEqualTo(1);
        assertThat(recovery.afterExecution()).isFalse();
        assertThat(recovery.deadlineAt()).isNotNull();
        assertThat(recovery.checks())
                .extracting(RecoveryStatusView.Check::criterionKey)
                .containsExactly("stream-lag-drained", "consumer-running");
        assertThat(recovery.checks()).allSatisfy(check -> {
            assertThat(check.result()).isNull();
            assertThat(check.reason()).isNull();
        });
        RecoveryStatusView.Sample lag = recovery.checks().get(0).samples().getFirst();
        assertThat(lag.status()).isEqualTo(RecoverySample.Status.SUCCEEDED);
        assertThat(lag.value()).isEqualTo(new ProjectedValue.Number(2180));
        assertThat(lag.sampledAt()).isNotNull();
        assertThat(recovery.checks().get(1).samples()).isEmpty();
        assertThat(incidents.getIncident(key).availableActions()).isEmpty();
    }

    /**
     * 终态：检查结果与原因取自持久化结果载荷，不在查询时重新判定（载荷记为 UNKNOWN/SAMPLE_FAILED，即使此刻重新求值会不同也照原样）；
     * 样本状态取判定时刻，成功样本附取值、失败样本附错误码。整体状态与检查三值分开。
     */
    @Test
    void aFinishedVerificationShowsThePersistedDecision() {
        long verification = verification("INCONCLUSIVE");
        long lagSample = successfulLagSample(verification, 1, 35L);
        long failedSample = failedServiceSample(verification, 1, "TIMEOUT");
        Instant decidedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        RecoveryVerificationResultV1 result = RecoveryVerificationResultV1.of(
                RecoveryOutcome.INCONCLUSIVE,
                decidedAt,
                List.of(
                        new RecoveryVerificationResultV1.Check(
                                "stream-lag-drained",
                                "积压达标",
                                true,
                                CriterionResult.TRUE,
                                CriterionReason.SATISFIED,
                                List.of(new RecoveryVerificationResultV1.Sample(
                                        1, lagSample, RecoverySample.Status.SUCCEEDED, decidedAt))),
                        new RecoveryVerificationResultV1.Check(
                                "consumer-running",
                                "消费者持续运行",
                                true,
                                CriterionResult.UNKNOWN,
                                CriterionReason.SAMPLE_FAILED,
                                List.of(new RecoveryVerificationResultV1.Sample(
                                        1, failedSample, RecoverySample.Status.FAILED, null)))));
        jdbc.update(
                "UPDATE recovery_verification SET result_payload = ?, result_summary = '恢复验证无法确认',"
                        + " finished_at = UTC_TIMESTAMP(3) WHERE id = ?",
                codecs.encode(RecoveryVerificationResultV1.SCHEMA_NAME, 1, result),
                verification);

        RecoveryStatusView recovery = incidents.getIncident(key).recovery();

        assertThat(recovery.status()).isEqualTo(RecoveryVerificationStatus.INCONCLUSIVE);
        assertThat(recovery.resultSummary()).isEqualTo("恢复验证无法确认");
        assertThat(recovery.finishedAt()).isNotNull();
        assertThat(recovery.checks())
                .extracting(check -> check.criterionKey() + "/" + check.result() + "/" + check.reason())
                .containsExactly("stream-lag-drained/TRUE/SATISFIED", "consumer-running/UNKNOWN/SAMPLE_FAILED");
        RecoveryStatusView.Sample lag = recovery.checks().get(0).samples().getFirst();
        assertThat(lag.value()).isEqualTo(new ProjectedValue.Number(35));
        assertThat(lag.sampledAt()).isEqualTo(decidedAt);
        RecoveryStatusView.Sample failed = recovery.checks().get(1).samples().getFirst();
        assertThat(failed.status()).isEqualTo(RecoverySample.Status.FAILED);
        assertThat(failed.value()).isNull();
        assertThat(failed.errorCode()).isEqualTo("TIMEOUT");
    }

    /** 多次验证只展示编号最大的一次（05 §35 不覆盖旧编号）。 */
    @Test
    void onlyTheLatestVerificationIsShown() {
        verification("PENDING");
        verification("PENDING");
        jdbc.update("UPDATE recovery_verification SET status = 'RUNNING', started_at = UTC_TIMESTAMP(3)"
                + " WHERE verification_no = 1");

        assertThat(incidents.getIncident(key).recovery().verificationNo()).isEqualTo(2);
        assertThat(incidents.getIncident(key).recovery().status()).isEqualTo(RecoveryVerificationStatus.PENDING);
    }

    // ---------------------------------------------------------------- 一致性读取

    /**
     * 第一次读取之后、其余各节读取之前，另一事务提交了新 Diagnosis、新事件与新版本：整份详情仍属于第一次读取的快照（版本、游标、
     * 当前判断与 availableActions 一致）；下一次读取才看到新事实。
     */
    @Test
    void theDetailIsOneConsistentSnapshotEvenWhenOthersCommitMidway() throws Exception {
        long before = event(incidentId(), "DIAGNOSIS_CREATED", "得出诊断");
        doAnswer(invocation -> {
                    Object snapshot = invocation.callRealMethod();
                    commitElsewhere(
                            "INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, summary,"
                                    + " impact_summary, termination_reason, created_at) VALUES ("
                                    + seeded.investigation().investigationId()
                                    + ", 1, 2, 'UNDETERMINED', '暂时无法确定原因', 'I', 'USER_STOPPED', UTC_TIMESTAMP(3))",
                            "INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type,"
                                    + " summary, payload, created_at) VALUES (" + incidentId()
                                    + ", 'DIAGNOSIS_CREATED', UTC_TIMESTAMP(3), 'SYSTEM', '新诊断', '{\"schemaName\":"
                                    + " \"t\", \"schemaVersion\": 1}', UTC_TIMESTAMP(3))",
                            "UPDATE incident SET lock_version = 8 WHERE id = " + incidentId());
                    return snapshot;
                })
                .doCallRealMethod()
                .when(queryRepository)
                .findSnapshot(anyString());

        IncidentDetailView first = incidents.getIncident(key);

        assertThat(first.version()).isEqualTo(7);
        assertThat(first.lastTimelineEventId()).isEqualTo(before);
        assertThat(first.currentAssessment().diagnosisVersion()).isEqualTo(1);
        assertThat(first.availableActions()).contains(IncidentAction.REQUEST_REMEDIATION);

        IncidentDetailView next = incidents.getIncident(key);
        assertThat(next.version()).isEqualTo(8);
        assertThat(next.lastTimelineEventId()).isGreaterThan(before);
        assertThat(next.currentAssessment().diagnosisVersion()).isEqualTo(2);
        assertThat(next.availableActions()).doesNotContain(IncidentAction.REQUEST_REMEDIATION);
    }

    // ---------------------------------------------------------------- TASK-086 availableActions

    /** 已诊断且结论可操作、有可用写动作：05 §23 的四个动作。 */
    @Test
    void anActionableDiagnosisOffersRemediation() {
        assertThat(incidents.getIncident(key).availableActions())
                .containsExactly(
                        IncidentAction.CONTINUE_INVESTIGATION,
                        IncidentAction.REQUEST_REMEDIATION,
                        IncidentAction.VERIFY_RECOVERY,
                        IncidentAction.CANCEL_INCIDENT);
    }

    /** 最新 Diagnosis 为 UNDETERMINED，或相关资源上没有可用写动作（S1/S2）：不提供请求处理建议。 */
    @Test
    void remediationIsNotOfferedWithoutAnActionableDiagnosisOrAnApplicableAction() {
        jdbc.update("UPDATE capability_binding SET enabled = FALSE WHERE capability_key = 'service.restart'");
        assertThat(incidents.getIncident(key).availableActions())
                .containsExactly(
                        IncidentAction.CONTINUE_INVESTIGATION,
                        IncidentAction.VERIFY_RECOVERY,
                        IncidentAction.CANCEL_INCIDENT);

        jdbc.update("UPDATE capability_binding SET enabled = TRUE WHERE capability_key = 'service.restart'");
        seeded.diagnosis(2, "UNDETERMINED", null);
        assertThat(incidents.getIncident(key).availableActions()).doesNotContain(IncidentAction.REQUEST_REMEDIATION);
    }

    /** 存在 PENDING Approval 时不提供继续调查（05 §28），与写路径的拒绝一致。 */
    @Test
    void aPendingApprovalBlocksContinuing() {
        remediation("PENDING");

        assertThat(incidents.getIncident(key).availableActions()).doesNotContain(IncidentAction.CONTINUE_INVESTIGATION);
    }

    /** 调查中：可停止与取消；本轮已请求停止后只剩取消。 */
    @Test
    void stopIsOfferedOnlyUntilItIsRequested() {
        String investigating =
                jdbc.queryForObject("SELECT incident_key FROM incident WHERE id = ?", String.class, otherIncidentId());
        assertThat(incidents.getIncident(investigating).availableActions())
                .containsExactly(IncidentAction.STOP_INVESTIGATION, IncidentAction.CANCEL_INCIDENT);

        jdbc.update(
                "UPDATE investigation SET stop_requested_at = UTC_TIMESTAMP(3), stop_requested_by = 'demo-user'"
                        + " WHERE incident_id = ?",
                otherIncidentId());
        IncidentDetailView stopping = incidents.getIncident(investigating);
        assertThat(stopping.investigation().stopRequested()).isTrue();
        assertThat(stopping.availableActions()).containsExactly(IncidentAction.CANCEL_INCIDENT);
    }

    /** 处理中、验证中与终态没有任何用户动作。 */
    @Test
    void executingVerifyingAndFinishedIncidentsOfferNothing() {
        for (String status : List.of("EXECUTING", "VERIFYING", "CANCELLED")) {
            jdbc.update("UPDATE incident SET status = ? WHERE id = ?", status, incidentId());
            assertThat(incidents.getIncident(key).availableActions()).as(status).isEmpty();
        }
        jdbc.update(
                "UPDATE incident SET status = 'RESOLVED', resolved_at = UTC_TIMESTAMP(3) WHERE id = ?", incidentId());
        assertThat(incidents.getIncident(key).availableActions()).isEmpty();
    }

    // ---------------------------------------------------------------- 数据

    private long incidentId() {
        return seeded.investigation().incidentId();
    }

    private long otherIncidentId() {
        return seeded.investigation().otherIncidentId();
    }

    private long event(long incidentId, String type, String summary) {
        jdbc.update(
                "INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary,"
                        + " payload, created_at) VALUES (?, ?, UTC_TIMESTAMP(3), 'SYSTEM', ?, '{\"schemaName\": \"t\","
                        + " \"schemaVersion\": 1}', UTC_TIMESTAMP(3))",
                incidentId,
                type,
                summary);
        return jdbc.queryForObject("SELECT MAX(id) FROM incident_timeline_event", Long.class);
    }

    private String createdIncident() {
        jdbc.update("INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status,"
                + " created_source, created_by, started_at, detected_at, created_at, updated_at) SELECT"
                + " 'INC-20260927-0003', id, 'T', 'I', 'CREATED', 'MANUAL', 'demo-user', UTC_TIMESTAMP(3),"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM managed_system");
        return "INC-20260927-0003";
    }

    /** 针对 Diagnosis v1 的方案（统计消费者 service.restart）及一条给定状态的 Approval。@return Approval id */
    private long remediation(String approvalStatus) {
        long diagnosis = jdbc.queryForObject(
                "SELECT id FROM diagnosis WHERE investigation_id = ? AND version_no = 1",
                Long.class,
                seeded.investigation().investigationId());
        jdbc.update(
                "INSERT INTO remediation_plan (incident_id, diagnosis_id, title, summary, status, created_at,"
                        + " updated_at) VALUES (?, ?, '恢复统计消费', '重新启动已停止的统计消费者。', 'ACTIVE',"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                incidentId(),
                diagnosis);
        long plan = jdbc.queryForObject("SELECT MAX(id) FROM remediation_plan", Long.class);
        jdbc.update(
                "INSERT INTO remediation_action (remediation_plan_id, capability_key, target_resource_id,"
                        + " parameter_schema_name, parameter_schema_version, parameter_payload, summary,"
                        + " expected_impact_summary, risk_level, requires_approval, created_at) VALUES (?,"
                        + " 'service.restart', ?, 'service.restart.request', 1, '{}', '重新启动统计消费者',"
                        + " '统计消费短暂中断', 'MEDIUM', TRUE, UTC_TIMESTAMP(3))",
                plan,
                seeded.consumer());
        long action = jdbc.queryForObject("SELECT MAX(id) FROM remediation_action", Long.class);
        boolean pending = "PENDING".equals(approvalStatus);
        jdbc.update(
                "INSERT INTO approval_request (remediation_action_id, status, requested_at, decided_by, decided_at,"
                        + " created_at, updated_at) VALUES (?, ?, UTC_TIMESTAMP(3), ?, ?, UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3))",
                action,
                approvalStatus,
                pending ? null : "demo-user",
                pending ? null : java.sql.Timestamp.from(Instant.now()));
        return jdbc.queryForObject("SELECT MAX(id) FROM approval_request", Long.class);
    }

    private long policyId;

    private long policy() {
        if (policyId == 0) {
            policyId = seeded.activateRecoveryPolicy(recoveryPolicies, "consumer-recovery");
        }
        return policyId;
    }

    /** 正式选择器按此刻配置生成的快照：先 Stream 积压，再消费者运行状态。 */
    private RecoveryPolicySnapshotV1 snapshot() {
        policy();
        return RecoveryPolicySnapshotV1.of(
                selector.select(resources.findById(seeded.consumer()).orElseThrow()));
    }

    /** 外部处理后的一次验证（没有 Execution），编号依次递增；终态先写占位结果，真实结果载荷由用例写入。 */
    private long verification(String status) {
        RecoveryPolicySnapshotV1 snapshot = snapshot();
        int number = jdbc.queryForObject(
                "SELECT COUNT(*) + 1 FROM recovery_verification WHERE incident_id = ?", Integer.class, incidentId());
        boolean terminal = !List.of("PENDING", "RUNNING").contains(status);
        jdbc.update(
                "INSERT INTO recovery_verification (incident_id, managed_resource_id, recovery_policy_id,"
                        + " recovery_policy_version, policy_snapshot, verification_no, status, result_summary,"
                        + " result_payload, deadline_at, started_at, finished_at, created_at, updated_at) VALUES"
                        + " (?, ?, ?, 1, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3) + INTERVAL 60 SECOND, ?, ?, UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3))",
                incidentId(),
                seeded.consumer(),
                policy(),
                codecs.encode(RecoveryPolicySnapshotV1.SCHEMA_NAME, 1, snapshot),
                number,
                status,
                terminal ? "待写入" : null,
                terminal ? "{\"schemaName\": \"recovery.verification.result\", \"schemaVersion\": 1}" : null,
                "PENDING".equals(status) ? null : java.sql.Timestamp.from(Instant.now()),
                terminal ? java.sql.Timestamp.from(Instant.now()) : null);
        return jdbc.queryForObject(
                "SELECT id FROM recovery_verification WHERE incident_id = ? AND verification_no = ?",
                Long.class,
                incidentId(),
                number);
    }

    /** 成功的积压样本：经正式 Codec 编码的 queue.inspect 结果，配置的消费组 lag 为给定值。 */
    private long successfulLagSample(long verification, int index, long lag) {
        String payload = codecs.encode(
                QueueInspectResultV1.SCHEMA_NAME,
                QueueInspectResultV1.SCHEMA_VERSION,
                new QueueInspectResultV1(
                        QueueInspectResultV1.QueueType.REDIS_STREAM,
                        1000,
                        null,
                        null,
                        List.of(
                                new QueueInspectResultV1.ConsumerGroup("other-group", 1, 0, 9L, null, null),
                                new QueueInspectResultV1.ConsumerGroup(GROUP, 1, 0, lag, null, null))));
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, recovery_verification_id, criterion_key, sample_index,"
                        + " capability_key, managed_resource_id, status, request_schema_name, request_schema_version,"
                        + " request_payload, response_schema_name, response_schema_version, response_payload,"
                        + " started_at, finished_at, duration_ms, created_at, updated_at) VALUES (?, ?,"
                        + " 'stream-lag-drained', ?, 'queue.inspect', ?, 'SUCCEEDED', 'queue.inspect.request', 1,"
                        + " '{}', ?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 5, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                incidentId(),
                verification,
                index,
                seeded.investigation().streamId(),
                QueueInspectResultV1.SCHEMA_NAME,
                QueueInspectResultV1.SCHEMA_VERSION,
                payload);
        return sampleId(verification, "stream-lag-drained", index);
    }

    private long failedServiceSample(long verification, int index, String errorCode) {
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, recovery_verification_id, criterion_key, sample_index,"
                        + " capability_key, managed_resource_id, status, request_schema_name, request_schema_version,"
                        + " request_payload, started_at, finished_at, duration_ms, error_code, error_message,"
                        + " created_at, updated_at) VALUES (?, ?, 'consumer-running', ?, 'service.inspect', ?, 'FAILED',"
                        + " 'service.inspect.request', 1, '{}', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 5, ?, 'failed',"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                incidentId(),
                verification,
                index,
                seeded.consumer(),
                errorCode);
        return sampleId(verification, "consumer-running", index);
    }

    private long sampleId(long verification, String criterionKey, int index) {
        return jdbc.queryForObject(
                "SELECT id FROM capability_invocation WHERE recovery_verification_id = ? AND criterion_key = ?"
                        + " AND sample_index = ?",
                Long.class,
                verification,
                criterionKey,
                index);
    }

    /** 在独立连接上自动提交，模拟详情读取期间其他事务的写入。 */
    private static void commitElsewhere(String... statements) throws SQLException {
        try (Connection other =
                        DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = other.createStatement()) {
            for (String sql : statements) {
                statement.executeUpdate(sql);
            }
        }
    }
}
