package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
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
import io.github.ismoyuan.opspilot.application.diagnosis.DiagnosisApplicationService;
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
import io.github.ismoyuan.opspilot.application.investigation.step.AgentStepRecorder;
import io.github.ismoyuan.opspilot.application.investigation.step.StepAdmissionService;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证调查编排与 Intent 分派（08 TASK-040～041、07 §41～§43、ACC-FINAL-03/04/05）。AiDecisionPort 为脚本化替身：
 * 每个脚本函数收到真实构造的请求，可在“AI 运行中”提交 Stop、切换 run 或取消以制造确定的竞态；WorkDispatcher 为替身，避免后台 Worker
 * 与测试并行。
 */
@SpringBootTest
@Testcontainers
@Import({
    InvestigationOrchestrator.class,
    IntentDispatcher.class,
    GatedFakeCapabilityExecutor.class,
    InvestigationContextBuilder.class,
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
    JdbcTemplate jdbc;

    @MockitoBean
    AiDecisionPort ai;

    @MockitoBean
    WorkDispatcher dispatcher;

    @MockitoSpyBean
    GatedFakeCapabilityExecutor capabilities;

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

    /** AI 失败逐步记录并计数，达到阈值后下一次准入被拒，Worker 退出；不透明重试（02 §28）。 */
    @Test
    void aiFailuresStopTheLoopAtTheThreshold() {
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
        assertThat(incidentStatus()).isEqualTo("INVESTIGATING");
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

    // ---------------------------------------------------------------- helpers

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
