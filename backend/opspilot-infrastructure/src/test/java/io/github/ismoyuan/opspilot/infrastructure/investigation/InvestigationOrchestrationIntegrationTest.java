package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.AiCallMetadata;
import io.github.ismoyuan.opspilot.application.ai.AiDecisionPort;
import io.github.ismoyuan.opspilot.application.ai.InvestigationStepDecision;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CompleteInvestigation;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.DiagnosisDraftV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.HypothesisUpdate;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ProposeEvidenceLink;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ProposeHypothesis;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.QueueInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RequestCapability;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.UpdateHypothesis;
import io.github.ismoyuan.opspilot.application.capability.CapabilityDescriptorBuilder;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.diagnosis.DiagnosisApplicationService;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWorkSource;
import io.github.ismoyuan.opspilot.application.dispatch.StartupRecoveryCoordinator;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.evidence.EvidenceApplicationService;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisApplicationService;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisStatusRecorder;
import io.github.ismoyuan.opspilot.application.incident.CancelIncidentCommand;
import io.github.ismoyuan.opspilot.application.incident.IncidentApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.StopInvestigationCommand;
import io.github.ismoyuan.opspilot.application.investigation.context.InvestigationContextBuilder;
import io.github.ismoyuan.opspilot.application.investigation.orchestration.CapabilityRequestResult;
import io.github.ismoyuan.opspilot.application.investigation.orchestration.GatedFakeCapabilityExecutor;
import io.github.ismoyuan.opspilot.application.investigation.orchestration.IntentDispatcher;
import io.github.ismoyuan.opspilot.application.investigation.orchestration.InvestigationOrchestrator;
import io.github.ismoyuan.opspilot.application.investigation.orchestration.InvestigationTerminator;
import io.github.ismoyuan.opspilot.application.investigation.recovery.InvestigationInterruptionRecorder;
import io.github.ismoyuan.opspilot.application.investigation.step.AgentStepRecorder;
import io.github.ismoyuan.opspilot.application.investigation.step.IntentDisposition;
import io.github.ismoyuan.opspilot.application.investigation.step.StepAdmission;
import io.github.ismoyuan.opspilot.application.investigation.step.StepAdmissionService;
import io.github.ismoyuan.opspilot.application.investigation.step.StepDecisionOutcome;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
import java.time.Clock;
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
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证调查编排、Intent 分派、确定性收束与启动恢复（08 TASK-040～043、07 §41～§43、§51～§55、ACC-FINAL-02～05）。
 * AiDecisionPort 为脚本化替身：每个脚本函数收到真实构造的请求，可在“AI 运行中”提交 Stop、切换 run 或取消以制造确定的竞态，
 * 抛出异常即模拟 AI 调用失败；WorkDispatcher 为替身，避免后台 Worker 与测试并行，恢复用例在测试内以同步派发器运行 Worker。
 */
@SpringBootTest
@Testcontainers
@Import({
    InvestigationOrchestrator.class,
    InvestigationTerminator.class,
    InvestigationInterruptionRecorder.class,
    IntentDispatcher.class,
    GatedFakeCapabilityExecutor.class,
    InvestigationContextBuilder.class,
    CapabilityDescriptorBuilder.class,
    CapabilityProviderResolver.class,
    StepAdmissionService.class,
    AgentStepRecorder.class,
    HypothesisApplicationService.class,
    HypothesisStatusRecorder.class,
    EvidenceApplicationService.class,
    DiagnosisApplicationService.class,
    InvestigationApplicationService.class,
    IncidentApplicationService.class,
    ClockConfiguration.class
})
class InvestigationOrchestrationIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    InvestigationOrchestrator orchestrator;

    @Autowired
    InvestigationApplicationService investigations;

    @Autowired
    IncidentApplicationService incidents;

    @Autowired
    InvestigationTerminator terminator;

    @Autowired
    InvestigationInterruptionRecorder interruptions;

    @Autowired
    List<DispatchableWorkSource> workSources;

    @Autowired
    StepAdmissionService admissions;

    @MockitoSpyBean
    AgentStepRecorder recorder;

    @Autowired
    IntentDispatcher intents;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AiDecisionPort ai;

    @MockitoBean
    WorkDispatcher dispatcher;

    @MockitoSpyBean
    GatedFakeCapabilityExecutor capabilities;

    @MockitoSpyBean
    HypothesisApplicationService hypothesisService;

    InvestigationFixture fixture;
    long incident;
    long observation;
    final Deque<Function<InvestigationStepRequest, InvestigationStepResponse>> script = new ArrayDeque<>();
    final List<InvestigationStepRequest> requests = new ArrayList<>();

    @BeforeEach
    void seed() {
        fixture = InvestigationFixture.reset(jdbc);
        incident = fixture.incidentId();
        observation = fixture.observation(incident, fixture.investigationId(), "inv-1");
        script.clear();
        requests.clear();
        doAnswer(invocation -> {
                    InvestigationStepRequest request = invocation.getArgument(0);
                    requests.add(request);
                    return new InvestigationStepDecision(script.removeFirst().apply(request), AiCallMetadata.UNKNOWN);
                })
                .when(ai)
                .decideInvestigationStep(any(), any());
    }

    // ---------------------------------------------------------------- TASK-040

    /** 五类中四类领域 Intent 依次落账，下一步上下文看到上一步事实，合法 COMPLETE 形成 Diagnosis 后结束。 */
    @Test
    void intentsAreAppliedStepByStepUntilDiagnosed() {
        script.add(r -> hypothesis(r, "Statistics Consumer 已停止"));
        script.add(r -> hypothesis(r, "Producer 已停止生产"));
        script.add(
                r -> link(r, observation, hypothesisId(r, 0), EvidenceRelation.SUPPORTS, HypothesisStatus.SUPPORTED));
        script.add(r -> update(r, hypothesisId(r, 1), HypothesisStatus.REFUTED));
        script.add(
                r -> complete(r, DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED, hypothesisId(r, 0), evidenceIds(r)));

        orchestrator.runInvestigation(incident, 1);

        assertThat(dispositions()).containsExactly("APPLIED", "APPLIED", "APPLIED", "APPLIED", "APPLIED");
        assertThat(requests.get(1).hypotheses()).hasSize(1);
        assertThat(requests.get(4).evidence()).hasSize(1);
        assertThat(status("hypothesis", "title = 'Statistics Consumer 已停止'")).isEqualTo("SUPPORTED");
        assertThat(status("hypothesis", "title = 'Producer 已停止生产'")).isEqualTo("REFUTED");
        assertThat(incidentStatus()).isEqualTo("DIAGNOSED");
        assertThat(jdbc.queryForObject("SELECT conclusion_type FROM diagnosis", String.class))
                .isEqualTo("PRIMARY_CAUSE_IDENTIFIED");
        assertThat(count("diagnosis_evidence_ref")).isOne();
        assertThat(count("agent_step_record WHERE status = 'SUCCEEDED'")).isEqualTo(5);
    }

    /** 业务拒绝只回滚该 Intent：Step 与拒绝原因照常提交，循环继续（05 §83、07 §43）。 */
    @Test
    void rejectedIntentsAreAuditedWithoutWritesAndTheLoopContinues() {
        script.add(r -> hypothesis(r, "Statistics Consumer 已停止"));
        script.add(
                r -> link(r, observation, hypothesisId(r, 0), EvidenceRelation.SUPPORTS, HypothesisStatus.SUPPORTED));
        script.add(r -> link(r, observation, hypothesisId(r, 0), EvidenceRelation.REFUTES, HypothesisStatus.REFUTED));
        script.add(r -> update(r, hypothesisId(r, 0), HypothesisStatus.SUPPORTED));
        script.add(r -> complete(r, DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED, hypothesisId(r, 0), List.of()));
        script.add(r -> complete(r, DiagnosisConclusionType.UNDETERMINED, null, List.of()));

        orchestrator.runInvestigation(incident, 1);

        assertThat(dispositions()).containsExactly("APPLIED", "APPLIED", "REJECTED", "REJECTED", "REJECTED", "APPLIED");
        assertThat(rejectionCodes())
                .containsExactly(
                        "EVIDENCE_LINK_ALREADY_EXISTS", "REQUEST_VALIDATION_FAILED", "DIAGNOSIS_INVARIANT_VIOLATION");
        assertThat(jdbc.queryForObject(
                        "SELECT JSON_EXTRACT(output_payload, '$.disposition.reason') FROM agent_step_record"
                                + " WHERE step_no = 4",
                        String.class))
                .isEqualTo("\"ILLEGAL_TRANSITION\"");
        assertThat(count("evidence")).isOne();
        assertThat(jdbc.queryForObject("SELECT relation FROM evidence", String.class))
                .isEqualTo("SUPPORTS");
        assertThat(status("hypothesis", "title = 'Statistics Consumer 已停止'")).isEqualTo("SUPPORTED");
        assertThat(jdbc.queryForObject("SELECT conclusion_type FROM diagnosis", String.class))
                .isEqualTo("UNDETERMINED");
        assertThat(count("agent_step_record WHERE status = 'SUCCEEDED'")).isEqualTo(6);
    }

    /**
     * 引用范围（B10-R1）：run 2 中只能引用本轮观测与以前 Diagnosis 冻结的历史；旧 run 未被冻结的观测与证据即使通过新 Evidence
     * 或 Diagnosis 也不能进入。
     */
    @Test
    void referencesAreLimitedToTheCurrentRunAndFrozenHistory() {
        long frozenObservation = observation;
        long oldObservation = fixture.observation(incident, fixture.investigationId(), "inv-old");
        script.add(r -> hypothesis(r, "Statistics Consumer 已停止"));
        script.add(r -> link(r, frozenObservation, hypothesisId(r, 0), EvidenceRelation.SUPPORTS, null));
        script.add(r -> link(r, oldObservation, hypothesisId(r, 0), EvidenceRelation.CONTEXT, null));
        script.add(r -> complete(
                r,
                DiagnosisConclusionType.POSSIBLE_CAUSE,
                hypothesisId(r, 0),
                List.of(evidenceIds(r).getFirst())));
        orchestrator.runInvestigation(incident, 1);
        long oldEvidence =
                jdbc.queryForObject("SELECT id FROM evidence WHERE observation_id = ?", Long.class, oldObservation);

        continueToRun2();
        long currentObservation = fixture.observationInRun(incident, fixture.investigationId(), "inv-run2", 2);
        script.add(r -> hypothesis(r, "Producer 已停止生产"));
        script.add(r -> link(r, oldObservation, hypothesisId(r, 1), EvidenceRelation.CONTEXT, null));
        script.add(r -> link(r, frozenObservation, hypothesisId(r, 1), EvidenceRelation.CONTEXT, null));
        script.add(r -> link(r, currentObservation, hypothesisId(r, 0), EvidenceRelation.SUPPORTS, null));
        script.add(r -> complete(
                r, DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED, hypothesisId(r, 0), List.of(oldEvidence)));
        script.add(
                r -> complete(r, DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED, hypothesisId(r, 0), evidenceIds(r)));
        orchestrator.runInvestigation(incident, 2);

        assertThat(dispositions().subList(4, 10))
                .containsExactly("APPLIED", "REJECTED", "APPLIED", "APPLIED", "REJECTED", "APPLIED");
        assertThat(rejectionReasons()).containsExactly("OBSERVATION_OUT_OF_SCOPE", "EVIDENCE_OUT_OF_SCOPE");
        // run 2 的上下文不含旧 run 未冻结的观测与证据
        assertThat(requests.get(4).observations())
                .extracting(InvestigationStepRequest.Observation::id)
                .doesNotContain(oldObservation);
        assertThat(requests.get(4).evidence())
                .extracting(InvestigationStepRequest.Evidence::id)
                .doesNotContain(oldEvidence);
        assertThat(jdbc.queryForList(
                        "SELECT CAST(r.evidence_id AS SIGNED) FROM diagnosis_evidence_ref r JOIN diagnosis d"
                                + " ON d.id = r.diagnosis_id WHERE d.version_no = 2",
                        Long.class))
                .doesNotContain(oldEvidence)
                .hasSize(3);
        assertThat(incidentStatus()).isEqualTo("DIAGNOSED");
    }

    /** REQUEST_CAPABILITY 在结果提交后经准入 Gate（Fake）：通过后如实“未执行”，不建调用、不扣预算，循环继续。 */
    @Test
    void capabilityRequestsPassTheGateButAreNotExecutedYet() {
        script.add(r -> queueInspect(r));
        script.add(r -> complete(r, DiagnosisConclusionType.UNDETERMINED, null, List.of()));

        orchestrator.runInvestigation(incident, 1);

        assertThat(dispositions()).containsExactly("ACCEPTED", "APPLIED");
        verify(capabilities).execute(anyLong(), anyInt(), anyLong(), any());
        assertThat(count("capability_invocation WHERE correlation_id <> 'inv-1'"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT current_run_capability_count FROM investigation WHERE id = ?",
                        Integer.class,
                        fixture.investigationId()))
                .isZero();
        assertThat(incidentStatus()).isEqualTo("DIAGNOSED");
    }

    /** AI 失败逐步记录并计数，达到阈值后下一次准入被拒并以 AI_RUNTIME_UNAVAILABLE 收束；不透明重试（02 §28）。 */
    @Test
    void aiFailuresAtTheThresholdEndTheRunAsUndetermined() {
        doAnswer(invocation -> {
                    throw new ApplicationException(ErrorCode.AI_RUNTIME_TIMEOUT, "AI runtime did not answer in time");
                })
                .when(ai)
                .decideInvestigationStep(any(), any());

        orchestrator.runInvestigation(incident, 1);

        verify(ai, times(3)).decideInvestigationStep(any(), any());
        assertThat(count("agent_step_record WHERE status = 'FAILED' AND error_code = 'AI_RUNTIME_TIMEOUT'"))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT consecutive_ai_failure_count FROM investigation WHERE id = ?",
                        Integer.class,
                        fixture.investigationId()))
                .isEqualTo(3);
        assertTerminated("AI_RUNTIME_UNAVAILABLE", 1);
    }

    // ---------------------------------------------------------------- TASK-041

    /** Stop 先提交：没有新 Step、不问 AI、不经 Capability（ACC-FINAL-03）。 */
    @Test
    void stopCommittedFirstAdmitsNothing() {
        stop();

        orchestrator.runInvestigation(incident, 1);

        verify(ai, never()).decideInvestigationStep(any(), any());
        verify(capabilities, never()).execute(anyLong(), anyInt(), anyLong(), any());
        assertThat(count("agent_step_record")).isZero();
        assertTerminated("USER_STOPPED", 1);
    }

    /** 准入先提交、AI 运行中 Stop：在途结果只审计，不写 Hypothesis，不展开下一步（ACC-FINAL-04）。 */
    @Test
    void stopDuringTheAiCallAuditsNonCompleteIntents() {
        script.add(r -> {
            stop();
            return hypothesis(r, "Statistics Consumer 已停止");
        });

        orchestrator.runInvestigation(incident, 1);

        assertThat(dispositions()).containsExactly("STOPPED");
        assertThat(count("hypothesis WHERE investigation_id = " + fixture.investigationId()))
                .isZero();
        verify(ai, times(1)).decideInvestigationStep(any(), any());
        assertTerminated("USER_STOPPED", 1);
    }

    /** AI 运行中 Stop 后返回 REQUEST_CAPABILITY：不经 Capability，更不发 Provider（08 TASK-041）。 */
    @Test
    void stopDuringTheAiCallNeverReachesTheProvider() {
        script.add(r -> {
            stop();
            return queueInspect(r);
        });

        orchestrator.runInvestigation(incident, 1);

        assertThat(dispositions()).containsExactly("STOPPED");
        verify(capabilities, never()).execute(anyLong(), anyInt(), anyLong(), any());
        assertTerminated("USER_STOPPED", 1);
    }

    /** Capability 准入前 Stop 先提交：Gate 拒绝，没有调用记录也不扣预算（ACC-FINAL-03）。 */
    @Test
    void stopBeforeCapabilityAdmissionIsRejectedByTheGate() {
        List<CapabilityRequestResult> results = new ArrayList<>();
        doAnswer(invocation -> {
                    stop();
                    CapabilityRequestResult result = (CapabilityRequestResult) invocation.callRealMethod();
                    results.add(result);
                    return result;
                })
                .when(capabilities)
                .execute(anyLong(), anyInt(), anyLong(), any());
        script.add(r -> queueInspect(r));

        orchestrator.runInvestigation(incident, 1);

        assertThat(results)
                .containsExactly(new CapabilityRequestResult.Rejected(StepAdmissionRejection.STOP_REQUESTED));
        assertThat(count("capability_invocation WHERE correlation_id <> 'inv-1'"))
                .isZero();
        verify(ai, times(1)).decideInvestigationStep(any(), any());
        assertTerminated("USER_STOPPED", 1);
    }

    /** 同轮 Stop 后在途的合法 COMPLETE 可以收束（01 §11、05 §85）。 */
    @Test
    void sameRunCompleteAfterStopStillDiagnoses() {
        script.add(r -> {
            stop();
            return complete(r, DiagnosisConclusionType.UNDETERMINED, null, List.of());
        });

        orchestrator.runInvestigation(incident, 1);

        assertThat(dispositions()).containsExactly("APPLIED");
        assertThat(incidentStatus()).isEqualTo("DIAGNOSED");
        assertThat(jdbc.queryForObject("SELECT run_no FROM diagnosis", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT termination_reason FROM diagnosis", String.class))
                .isEqualTo("AGENT_COMPLETED");
    }

    /** 旧 run 的合法 COMPLETE 只审计，不建本轮 Diagnosis、不改新 run（ACC-FINAL-05）。 */
    @Test
    void oldRunCompleteDoesNotDiagnoseTheNewRun() {
        script.add(r -> {
            jdbc.update(
                    "UPDATE investigation SET current_run_no = 2, current_run_started_at = UTC_TIMESTAMP(3)"
                            + " WHERE id = ?",
                    fixture.investigationId());
            return complete(r, DiagnosisConclusionType.UNDETERMINED, null, List.of());
        });

        orchestrator.runInvestigation(incident, 1);

        assertThat(dispositions()).containsExactly("NOT_CURRENT");
        assertThat(count("diagnosis")).isZero();
        assertThat(incidentStatus()).isEqualTo("INVESTIGATING");
        verify(ai, times(1)).decideInvestigationStep(any(), any());
    }

    /** AI 运行中取消：迟到输出只审计，不写领域对象。 */
    @Test
    void cancellationDuringTheAiCallLeavesOnlyTheAudit() {
        script.add(r -> {
            incidents.cancelIncident(new CancelIncidentCommand(key(), version(), "误报", "demo-user"));
            return hypothesis(r, "Statistics Consumer 已停止");
        });

        orchestrator.runInvestigation(incident, 1);

        assertThat(dispositions()).containsExactly("NOT_CURRENT");
        assertThat(count("hypothesis WHERE investigation_id = " + fixture.investigationId()))
                .isZero();
        assertThat(incidentStatus()).isEqualTo("CANCELLED");
    }

    /**
     * Diagnosis 版本号在取得锁之后读取（TASK-026 修复）：结果事务等待 Incident 锁期间同一调查提交了新版本，取得锁后的合法 COMPLETE
     * 仍得到下一个版本号。结果事务的第一次一致性读必须发生在取锁之后，否则持锁后的普通读会看到旧快照而重复分配版本号。
     */
    @Test
    void completeAfterWaitingForTheIncidentLockTakesTheNextVersion() throws Exception {
        long stepId =
                ((StepAdmission.Admitted) admissions.admit(incident, 1)).step().id();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM incident WHERE id = ? FOR UPDATE", Long.class, incident);
                    locked.countDown();
                    await(release);
                    // 结果事务等锁期间，同一调查的另一次创建先提交 v1
                    jdbc.update(
                            "INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, summary,"
                                    + " impact_summary, termination_reason, created_at) VALUES (?, 1, 1, 'UNDETERMINED',"
                                    + " '先提交的诊断', 'I', 'USER_STOPPED', UTC_TIMESTAMP(3))",
                            fixture.investigationId());
                }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<StepDecisionOutcome> recorded = CompletableFuture.supplyAsync(() -> recorder.recordDecision(
                stepId,
                new InvestigationStepDecision(
                        new InvestigationStepResponse.CompleteInvestigationStep(
                                1,
                                1,
                                stepId,
                                new CompleteInvestigation(new DiagnosisDraftV1(
                                        DiagnosisConclusionType.UNDETERMINED, null, "诊断摘要", "统计数据延迟", List.of()))),
                        AiCallMetadata.UNKNOWN),
                1,
                intents));
        awaitIncidentLockWait();
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);

        assertThat(recorded.get(10, TimeUnit.SECONDS).disposition().outcome())
                .isEqualTo(IntentDisposition.Outcome.APPLIED);
        assertThat(jdbc.queryForList("SELECT termination_reason FROM diagnosis ORDER BY version_no", String.class))
                .containsExactly("USER_STOPPED", "AGENT_COMPLETED");
        assertThat(incidentStatus()).isEqualTo("DIAGNOSED");
    }

    /**
     * 结果事务失败（TASK-040/043 修复）：非业务异常使结果事务整体回滚后，以独立短事务把该 Step 记为 FAILED/INTERNAL_ERROR，
     * 没有领域写入，连续 AI 失败计数不变；本次唤醒到此结束——不再问 AI、不新建 Step；下一次唤醒重新经准入正常完成，不留 RUNNING。
     */
    @Test
    void resultRecordingFailureClosesTheStepAndEndsThisWakeUp() {
        update("consecutive_ai_failure_count = 2");
        doThrow(new RecoverableDataAccessException("simulated database failure"))
                .doCallRealMethod()
                .when(hypothesisService)
                .proposeHypothesis(any());
        script.add(r -> hypothesis(r, "Statistics Consumer 已停止"));

        assertThatThrownBy(() -> orchestrator.runInvestigation(incident, 1))
                .isInstanceOf(RecoverableDataAccessException.class);

        assertThat(jdbc.queryForMap("SELECT status, error_code, error_message, output_payload FROM agent_step_record"))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "INTERNAL_ERROR")
                .containsEntry("error_message", "AI step outcome could not be recorded")
                .containsEntry("output_payload", null);
        assertThat(count("hypothesis WHERE investigation_id = " + fixture.investigationId()))
                .isZero();
        assertThat(failures()).isEqualTo(2);
        verify(ai, times(1)).decideInvestigationStep(any(), any());
        assertThat(incidentStatus()).isEqualTo("INVESTIGATING");

        // 下一次唤醒（补派发）
        script.add(r -> complete(r, DiagnosisConclusionType.UNDETERMINED, null, List.of()));
        orchestrator.runInvestigation(incident, 1);

        assertThat(jdbc.queryForList("SELECT status FROM agent_step_record ORDER BY step_no", String.class))
                .containsExactly("FAILED", "SUCCEEDED");
        assertThat(incidentStatus()).isEqualTo("DIAGNOSED");
    }

    /**
     * 保存失败且当时的收尾也失败（TASK-040/043 修复复审 P2）：孤立的 RUNNING Step 保留到本 Incident 的下一个 Worker，在准入前补完；
     * 补完失败的那次唤醒不准入新 Step、不问 AI；补完成功后才准入新 Step 并正常完成。只补完本 Incident 的 Step——其他 Incident 仍在
     * 运行的 Step 不受影响。
     */
    @Test
    void orphanLeftByADoubleFailureIsClosedBeforeAnyNewStep() {
        jdbc.update(
                "INSERT INTO agent_step_record (incident_id, investigation_id, run_no, step_no, status, started_at,"
                        + " created_at, updated_at) VALUES (?, ?, 1, 1, 'RUNNING', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3))",
                fixture.otherIncidentId(),
                fixture.otherInvestigationId());
        doThrow(new RecoverableDataAccessException("simulated database failure"))
                .doCallRealMethod()
                .when(hypothesisService)
                .proposeHypothesis(any());
        doThrow(new RecoverableDataAccessException("close failed too"))
                .doCallRealMethod()
                .when(recorder)
                .closeUnrecorded(anyLong(), anyLong());
        script.add(r -> hypothesis(r, "Statistics Consumer 已停止"));

        assertThatThrownBy(() -> orchestrator.runInvestigation(incident, 1))
                .isInstanceOf(RecoverableDataAccessException.class)
                .satisfies(ex -> assertThat(ex.getSuppressed()).hasSize(1));
        assertThat(stepStatuses(fixture.investigationId())).containsExactly("RUNNING");

        // 下一次唤醒：补完再次失败 → 不准入新 Step、不问 AI
        doThrow(new RecoverableDataAccessException("still failing"))
                .doCallRealMethod()
                .when(recorder)
                .closeOrphanedSteps(anyLong());
        assertThatThrownBy(() -> orchestrator.runInvestigation(incident, 1))
                .isInstanceOf(RecoverableDataAccessException.class);
        assertThat(stepStatuses(fixture.investigationId())).containsExactly("RUNNING");
        verify(ai, times(1)).decideInvestigationStep(any(), any());

        // 再下一次唤醒：先补完孤立 Step，再准入新 Step 并完成
        script.add(r -> complete(r, DiagnosisConclusionType.UNDETERMINED, null, List.of()));
        orchestrator.runInvestigation(incident, 1);

        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(status, '/', COALESCE(error_code, '-')) FROM agent_step_record"
                                + " WHERE investigation_id = ? ORDER BY step_no",
                        String.class,
                        fixture.investigationId()))
                .containsExactly("FAILED/INTERNAL_ERROR", "SUCCEEDED/-");
        verify(ai, times(2)).decideInvestigationStep(any(), any());
        assertThat(failures()).isZero();
        assertThat(incidentStatus()).isEqualTo("DIAGNOSED");
        assertThat(stepStatuses(fixture.otherInvestigationId())).containsExactly("RUNNING");
    }

    /**
     * 取消后的孤立 Step（TASK-040/043 修复 Review-2 P2）：保存与即时收尾都失败后用户取消了 Incident。存活期补派发仍会为该 Incident
     * 派发（单飞保护下），Worker 只补完审计后退出：不重开调查、不问 AI、不改取消状态与版本；补完后不再被派发。
     */
    @Test
    void orphanOfACancelledIncidentIsClosedByTheRescanWithoutReopeningIt() {
        doThrow(new RecoverableDataAccessException("simulated database failure"))
                .doCallRealMethod()
                .when(hypothesisService)
                .proposeHypothesis(any());
        doThrow(new RecoverableDataAccessException("close failed too"))
                .doCallRealMethod()
                .when(recorder)
                .closeUnrecorded(anyLong(), anyLong());
        script.add(r -> hypothesis(r, "Statistics Consumer 已停止"));
        assertThatThrownBy(() -> orchestrator.runInvestigation(incident, 1))
                .isInstanceOf(RecoverableDataAccessException.class);
        incidents.cancelIncident(new CancelIncidentCommand(key(), version(), "误报", "demo-user"));
        long cancelledVersion = version();
        List<DispatchableWork> dispatched = new ArrayList<>();

        liveCoordinator(dispatched).redispatchPending();

        assertThat(dispatched).contains(new DispatchableWork.Investigation(incident, 1));
        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(status, '/', COALESCE(error_code, '-')) FROM agent_step_record"
                                + " WHERE investigation_id = ?",
                        String.class,
                        fixture.investigationId()))
                .containsExactly("FAILED/INTERNAL_ERROR");
        assertThat(incidentStatus()).isEqualTo("CANCELLED");
        assertThat(version()).isEqualTo(cancelledVersion);
        assertThat(count("diagnosis")).isZero();
        verify(ai, times(1)).decideInvestigationStep(any(), any());

        dispatched.clear();
        liveCoordinator(dispatched).redispatchPending();
        assertThat(dispatched).doesNotContain(new DispatchableWork.Investigation(incident, 1));
    }

    /** 结果已提交后的失败（此处为 Capability 端口抛出）不改写已提交的 Step 终态，也不新建 Step。 */
    @Test
    void failureAfterTheOutcomeIsCommittedKeepsTheCommittedStep() {
        doThrow(new IllegalStateException("capability port failed"))
                .when(capabilities)
                .execute(anyLong(), anyInt(), anyLong(), any());
        script.add(r -> queueInspect(r));

        assertThatThrownBy(() -> orchestrator.runInvestigation(incident, 1)).isInstanceOf(IllegalStateException.class);

        assertThat(dispositions()).containsExactly("ACCEPTED");
        assertThat(jdbc.queryForMap("SELECT status, error_code FROM agent_step_record"))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("error_code", null);
        assertThat(recorder.closeUnrecorded(jdbc.queryForObject("SELECT id FROM agent_step_record", Long.class), 1))
                .isFalse();
        verify(ai, times(1)).decideInvestigationStep(any(), any());
    }

    // ---------------------------------------------------------------- TASK-042

    /** 额度耗尽：不再问 AI，以真实原因形成 UNDETERMINED；只结束本轮，Continue 后新 run 可正常调查（01 §9、§12）。 */
    @Test
    void budgetExhaustionEndsOnlyTheCurrentRun() {
        jdbc.update(
                "UPDATE investigation SET current_run_capability_count = 12, capability_call_count = 12 WHERE id = ?",
                fixture.investigationId());

        orchestrator.runInvestigation(incident, 1);

        verify(ai, never()).decideInvestigationStep(any(), any());
        assertTerminated("CAPABILITY_BUDGET_EXHAUSTED", 1);
        assertThat(jdbc.queryForObject(
                        "SELECT actor_type FROM incident_timeline_event WHERE event_type = 'DIAGNOSIS_CREATED'",
                        String.class))
                .isEqualTo("SYSTEM");

        continueToRun2();
        script.add(r -> complete(r, DiagnosisConclusionType.UNDETERMINED, null, List.of()));
        orchestrator.runInvestigation(incident, 2);

        assertThat(requests).extracting(InvestigationStepRequest::runNo).containsExactly(2);
        assertThat(jdbc.queryForList("SELECT termination_reason FROM diagnosis ORDER BY version_no", String.class))
                .containsExactly("CAPABILITY_BUDGET_EXHAUSTED", "AGENT_COMPLETED");
        assertThat(jdbc.queryForObject(
                        "SELECT capability_call_count FROM investigation WHERE id = ?",
                        Integer.class,
                        fixture.investigationId()))
                .isEqualTo(12);
    }

    /** 本轮截止在 AI 调用期间到达：在途结果照常处理，下一次准入以 INVESTIGATION_TIMEOUT 收束，不再问 AI。 */
    @Test
    void deadlinePassingDuringTheRunEndsItAsTimeout() {
        script.add(r -> {
            jdbc.update(
                    "UPDATE investigation SET current_run_started_at = UTC_TIMESTAMP(3) - INTERVAL 481 SECOND"
                            + " WHERE id = ?",
                    fixture.investigationId());
            return hypothesis(r, "Statistics Consumer 已停止");
        });

        orchestrator.runInvestigation(incident, 1);

        assertThat(dispositions()).containsExactly("APPLIED");
        verify(ai, times(1)).decideInvestigationStep(any(), any());
        assertTerminated("INVESTIGATION_TIMEOUT", 1);
    }

    /**
     * 收束只冻结本轮建立的 Evidence：run 1 Stop 收束冻结 e1；run 2 上下文仍可见 e1（冻结历史），但 run 2 连续 AI 失败收束时只冻结
     * 本轮的 e2，不复制历史 Diagnosis 的引用（07 §53）。
     */
    @Test
    void terminationFreezesOnlyThisRunsEvidence() {
        script.add(r -> hypothesis(r, "Statistics Consumer 已停止"));
        script.add(r -> link(r, observation, hypothesisId(r, 0), EvidenceRelation.SUPPORTS, null));
        script.add(r -> {
            stop();
            return hypothesis(r, "Producer 已停止生产");
        });
        orchestrator.runInvestigation(incident, 1);
        long e1 = jdbc.queryForObject("SELECT id FROM evidence", Long.class);

        continueToRun2();
        long currentObservation = fixture.observationInRun(incident, fixture.investigationId(), "inv-run2", 2);
        script.add(r -> link(r, currentObservation, hypothesisId(r, 0), EvidenceRelation.SUPPORTS, null));
        for (int i = 0; i < 3; i++) {
            script.add(r -> {
                throw new ApplicationException(ErrorCode.AI_RUNTIME_UNAVAILABLE, "AI runtime is not reachable");
            });
        }
        orchestrator.runInvestigation(incident, 2);
        long e2 = jdbc.queryForObject("SELECT id FROM evidence WHERE id <> ?", Long.class, e1);

        assertThat(requests.get(3).evidence())
                .extracting(InvestigationStepRequest.Evidence::id)
                .containsExactly(e1);
        assertThat(jdbc.queryForList("SELECT termination_reason FROM diagnosis ORDER BY version_no", String.class))
                .containsExactly("USER_STOPPED", "AI_RUNTIME_UNAVAILABLE");
        assertThat(frozenEvidence(1)).containsExactly(e1);
        assertThat(frozenEvidence(2)).containsExactly(e2);
        assertThat(jdbc.queryForList("SELECT conclusion_type FROM diagnosis", String.class))
                .containsOnly("UNDETERMINED");
    }

    /** 收束在持锁后重新判定：没有退出条件或 run 已不是当前时什么也不写。 */
    @Test
    void terminatorWritesNothingWithoutACurrentExitCondition() {
        assertThat(terminator.terminate(incident, 1)).isEmpty();

        stop();
        assertThat(terminator.terminate(incident, 2)).isEmpty();

        assertThat(count("diagnosis")).isZero();
        assertThat(incidentStatus()).isEqualTo("INVESTIGATING");
    }

    // ---------------------------------------------------------------- TASK-043

    /**
     * 重启恢复原 run（ACC-FINAL-02）：旧进程 RUNNING Step 与调查调用标 PROCESS_INTERRUPTED，不伪造 Observation；run、起点、
     * Stop、额度与连续失败计数均不变（中断不计入模型失败），随后派发原 run 继续调查。
     */
    @Test
    void restartMarksInterruptedWorkAndContinuesTheSameRun() {
        jdbc.update(
                "UPDATE investigation SET current_run_started_at = UTC_TIMESTAMP(3) - INTERVAL 120 SECOND,"
                        + " current_run_capability_count = 5, capability_call_count = 5,"
                        + " consecutive_ai_failure_count = 2 WHERE id = ?",
                fixture.investigationId());
        long step = oldRunningStep(1, 60);
        long call = oldRunningInvestigationCall(30);
        Map<String, Object> before = runControl();
        List<Map<String, Object>> seenDuringTheCall = new ArrayList<>();
        script.add(r -> {
            seenDuringTheCall.add(runControl());
            return complete(r, DiagnosisConclusionType.UNDETERMINED, null, List.of());
        });
        List<DispatchableWork> dispatched = new ArrayList<>();

        coordinator(dispatched, true).recoverAfterStartup();

        assertThat(jdbc.queryForMap("SELECT status, error_code, latency_ms FROM agent_step_record WHERE id = ?", step))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "PROCESS_INTERRUPTED")
                .containsEntry("latency_ms", null);
        Map<String, Object> interrupted = jdbc.queryForMap(
                "SELECT status, error_code, CAST(duration_ms AS SIGNED) AS duration_ms, response_payload"
                        + " FROM capability_invocation WHERE id = ?",
                call);
        assertThat(interrupted)
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "PROCESS_INTERRUPTED")
                .containsEntry("response_payload", null);
        assertThat((Long) interrupted.get("duration_ms")).isGreaterThanOrEqualTo(29_000L);
        assertThat(count("observation WHERE capability_invocation_id = " + call))
                .isZero();
        assertThat(seenDuringTheCall).containsExactly(before);
        assertThat(dispatched).contains(new DispatchableWork.Investigation(incident, 1));
        assertThat(requests).extracting(InvestigationStepRequest::runNo).containsExactly(1);
        assertThat(jdbc.queryForObject("SELECT termination_reason FROM diagnosis", String.class))
                .isEqualTo("AGENT_COMPLETED");
    }

    /** Stop 提交后崩溃：启动时不再问 AI，按本轮事实以 USER_STOPPED 收束（07 §53）。 */
    @Test
    void stopThenCrashEndsTheRunWithoutAskingTheAi() {
        stop();
        long step = oldRunningStep(1, 1);

        coordinator(new ArrayList<>(), true).recoverAfterStartup();

        verify(ai, never()).decideInvestigationStep(any(), any());
        assertThat(status("agent_step_record", "id = " + step)).isEqualTo("FAILED");
        assertTerminated("USER_STOPPED", 1);
    }

    /** 停机时间计入本轮：重启时已过截止直接以 INVESTIGATION_TIMEOUT 收束，不刷新 deadline（01 §9）。 */
    @Test
    void runExpiredWhileDownEndsAsTimeoutOnRestart() {
        jdbc.update(
                "UPDATE investigation SET current_run_started_at = UTC_TIMESTAMP(3) - INTERVAL 481 SECOND"
                        + " WHERE id = ?",
                fixture.investigationId());

        coordinator(new ArrayList<>(), true).recoverAfterStartup();

        verify(ai, never()).decideInvestigationStep(any(), any());
        assertTerminated("INVESTIGATION_TIMEOUT", 1);
    }

    /** 中断只针对本进程启动之前开始的记录；启动标记成功后补派发不再标记，当前 JVM 的 RUNNING 始终不受影响（07 §51）。 */
    @Test
    void onlyRecordsOfThePreviousProcessAreMarkedInterrupted() {
        long old = oldRunningStep(1, 60);
        List<DispatchableWork> dispatched = new ArrayList<>();
        StartupRecoveryCoordinator coordinator = coordinator(dispatched, false);
        long mine = runningStep(2, "UTC_TIMESTAMP(3) + INTERVAL 1 SECOND");

        coordinator.recoverAfterStartup();
        long leftover = runningStep(3, "UTC_TIMESTAMP(3) - INTERVAL 90 SECOND");
        coordinator.redispatchPending();

        assertThat(status("agent_step_record", "id = " + old)).isEqualTo("FAILED");
        assertThat(status("agent_step_record", "id = " + mine)).isEqualTo("RUNNING");
        assertThat(status("agent_step_record", "id = " + leftover)).isEqualTo("RUNNING");
        assertThat(dispatched)
                .filteredOn(new DispatchableWork.Investigation(incident, 1)::equals)
                .hasSize(2);
    }

    /** 唤醒丢失（afterCommit 前崩溃、线程池拒绝）的已 Stop 调查由补派发收束，不会永久停在 INVESTIGATING。 */
    @Test
    void rescanEndsAStoppedRunWhoseWakeUpWasLost() {
        stop();

        coordinator(new ArrayList<>(), true).redispatchPending();

        verify(ai, never()).decideInvestigationStep(any(), any());
        assertTerminated("USER_STOPPED", 1);
    }

    // ---------------------------------------------------------------- helpers

    /** 直接确认另一连接正在等待 Incident 行锁（而不是以 sleep 推断）。 */
    private void awaitIncidentLockWait() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID <> CONNECTION_ID()"
                            + " AND INFO LIKE '%FROM incident%FOR UPDATE%'",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("result transaction never waited for the incident lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertTerminated(String reason, int runNo) {
        assertThat(incidentStatus()).isEqualTo("DIAGNOSED");
        Map<String, Object> latest = jdbc.queryForMap(
                "SELECT conclusion_type, primary_hypothesis_id, termination_reason, run_no, impact_summary"
                        + " FROM diagnosis ORDER BY version_no DESC LIMIT 1");
        assertThat(latest)
                .containsEntry("conclusion_type", "UNDETERMINED")
                .containsEntry("primary_hypothesis_id", null)
                .containsEntry("termination_reason", reason)
                .containsEntry("impact_summary", "I");
        assertThat(((Number) latest.get("run_no")).intValue()).isEqualTo(runNo);
    }

    private void update(String assignments) {
        jdbc.update("UPDATE investigation SET " + assignments + " WHERE id = ?", fixture.investigationId());
    }

    private List<String> stepStatuses(long investigationId) {
        return jdbc.queryForList(
                "SELECT status FROM agent_step_record WHERE investigation_id = ? ORDER BY step_no",
                String.class,
                investigationId);
    }

    private int failures() {
        return jdbc.queryForObject(
                "SELECT consecutive_ai_failure_count FROM investigation WHERE id = ?",
                Integer.class,
                fixture.investigationId());
    }

    private List<Long> frozenEvidence(int versionNo) {
        return jdbc.queryForList(
                "SELECT CAST(r.evidence_id AS SIGNED) FROM diagnosis_evidence_ref r JOIN diagnosis d"
                        + " ON d.id = r.diagnosis_id WHERE d.version_no = ? ORDER BY r.evidence_id",
                Long.class,
                versionNo);
    }

    private Map<String, Object> runControl() {
        return jdbc.queryForMap(
                "SELECT current_run_no, current_run_started_at, current_run_capability_count, capability_call_count,"
                        + " consecutive_ai_failure_count, stop_requested_at FROM investigation WHERE id = ?",
                fixture.investigationId());
    }

    /** 旧进程在 {@code secondsAgo} 秒前开始、崩溃时仍为 RUNNING 的 Step。 */
    private long oldRunningStep(int stepNo, int secondsAgo) {
        return runningStep(stepNo, "UTC_TIMESTAMP(3) - INTERVAL " + secondsAgo + " SECOND");
    }

    private long runningStep(int stepNo, String startedAtSql) {
        jdbc.update(
                "INSERT INTO agent_step_record (incident_id, investigation_id, run_no, step_no, status, started_at,"
                        + " created_at, updated_at) VALUES (?, ?, 1, ?, 'RUNNING', " + startedAtSql + ", "
                        + startedAtSql + ", " + startedAtSql + ")",
                incident,
                fixture.investigationId(),
                stepNo);
        return jdbc.queryForObject(
                "SELECT id FROM agent_step_record WHERE investigation_id = ? AND step_no = ?",
                Long.class,
                fixture.investigationId(),
                stepNo);
    }

    private long oldRunningInvestigationCall(int secondsAgo) {
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key,"
                        + " managed_resource_id, status, request_schema_name, request_schema_version, request_payload,"
                        + " started_at, correlation_id, created_at, updated_at) SELECT ?, ?, 1, 'queue.inspect', id,"
                        + " 'RUNNING', 'queue.inspect.request', 1, '{}', UTC_TIMESTAMP(3) - INTERVAL ? SECOND,"
                        + " 'inv-running', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM managed_resource",
                incident,
                fixture.investigationId(),
                secondsAgo);
        return jdbc.queryForObject(
                "SELECT id FROM capability_invocation WHERE correlation_id = 'inv-running'", Long.class);
    }

    /**
     * 与生产相同的恢复入口，使用真实的调查工作来源与中断记录；派发器同步记录，并（按需）只为本用例的 Incident 运行 Worker，
     * fixture 中另一条调查只记录不运行。
     */
    private StartupRecoveryCoordinator coordinator(List<DispatchableWork> dispatched, boolean runWorker) {
        WorkDispatcher inline = work -> {
            dispatched.add(work);
            if (runWorker
                    && work instanceof DispatchableWork.Investigation investigation
                    && investigation.incidentId() == incident) {
                orchestrator.runInvestigation(investigation.incidentId(), investigation.runNo());
            }
        };
        return new StartupRecoveryCoordinator(workSources, List.of(interruptions), inline, Clock.systemUTC());
    }

    /** 存活期间的补派发：本进程的启动中断标记已完成（无 recorder），派发器同步为本用例的 Incident 运行 Worker。 */
    private StartupRecoveryCoordinator liveCoordinator(List<DispatchableWork> dispatched) {
        WorkDispatcher inline = work -> {
            dispatched.add(work);
            if (work instanceof DispatchableWork.Investigation investigation
                    && investigation.incidentId() == incident) {
                orchestrator.runInvestigation(investigation.incidentId(), investigation.runNo());
            }
        };
        return new StartupRecoveryCoordinator(workSources, List.of(), inline, Clock.systemUTC());
    }

    private void stop() {
        investigations.stopInvestigation(new StopInvestigationCommand(key(), version(), "demo-user"));
    }

    private void continueToRun2() {
        investigations.continueInvestigation(
                new io.github.ismoyuan.opspilot.application.investigation.ContinueInvestigationCommand(
                        key(), version(), "demo-user"));
    }

    private String key() {
        return jdbc.queryForObject("SELECT incident_key FROM incident WHERE id = ?", String.class, incident);
    }

    private long version() {
        return jdbc.queryForObject(
                "SELECT CAST(lock_version AS SIGNED) FROM incident WHERE id = ?", Long.class, incident);
    }

    private String incidentStatus() {
        return jdbc.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, incident);
    }

    private String status(String table, String where) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE " + where, String.class);
    }

    private int count(String tableAndWhere) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + tableAndWhere, Integer.class);
    }

    private List<String> dispositions() {
        return jdbc.queryForList(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(output_payload, '$.disposition.outcome')) FROM agent_step_record"
                        + " ORDER BY step_no",
                String.class);
    }

    private List<String> rejectionCodes() {
        return jdbc.queryForList(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(output_payload, '$.disposition.code')) FROM agent_step_record"
                        + " WHERE JSON_UNQUOTE(JSON_EXTRACT(output_payload, '$.disposition.outcome')) = 'REJECTED'"
                        + " ORDER BY step_no",
                String.class);
    }

    private List<String> rejectionReasons() {
        return jdbc.queryForList(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(output_payload, '$.disposition.reason')) FROM agent_step_record"
                        + " WHERE JSON_UNQUOTE(JSON_EXTRACT(output_payload, '$.disposition.outcome')) = 'REJECTED'"
                        + " ORDER BY step_no",
                String.class);
    }

    private static long hypothesisId(InvestigationStepRequest request, int index) {
        return request.hypotheses().get(index).id();
    }

    private static List<Long> evidenceIds(InvestigationStepRequest request) {
        return request.evidence().stream()
                .map(InvestigationStepRequest.Evidence::id)
                .toList();
    }

    private static InvestigationStepResponse hypothesis(InvestigationStepRequest r, String title) {
        return new InvestigationStepResponse.ProposeHypothesisStep(
                1, r.runNo(), r.stepId(), new ProposeHypothesis(title, null));
    }

    private static InvestigationStepResponse update(
            InvestigationStepRequest r, long hypothesisId, HypothesisStatus to) {
        return new InvestigationStepResponse.UpdateHypothesisStep(
                1, r.runNo(), r.stepId(), new UpdateHypothesis(hypothesisId, to, "依据新观测调整"));
    }

    private static InvestigationStepResponse link(
            InvestigationStepRequest r,
            long observationId,
            long hypothesisId,
            EvidenceRelation relation,
            HypothesisStatus update) {
        return new InvestigationStepResponse.ProposeEvidenceLinkStep(
                1,
                r.runNo(),
                r.stepId(),
                new ProposeEvidenceLink(observationId, hypothesisId, relation, "消费者组持续积压"),
                update == null ? null : new HypothesisUpdate(hypothesisId, update));
    }

    private static InvestigationStepResponse complete(
            InvestigationStepRequest r, DiagnosisConclusionType type, Long primary, List<Long> evidenceIds) {
        return new InvestigationStepResponse.CompleteInvestigationStep(
                1,
                r.runNo(),
                r.stepId(),
                new CompleteInvestigation(new DiagnosisDraftV1(type, primary, "诊断摘要", "统计数据延迟", evidenceIds)));
    }

    private static InvestigationStepResponse queueInspect(InvestigationStepRequest r) {
        return new InvestigationStepResponse.RequestCapabilityStep(
                1,
                r.runNo(),
                r.stepId(),
                new RequestCapability.QueueInspect(13, new QueueInspectArgumentsV1(), "核对消费者组积压。"));
    }
}
