package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import io.github.ismoyuan.opspilot.application.diagnosis.CreateDiagnosisCommand;
import io.github.ismoyuan.opspilot.application.diagnosis.DiagnosisApplicationService;
import io.github.ismoyuan.opspilot.application.diagnosis.DiagnosisRepository;
import io.github.ismoyuan.opspilot.application.evidence.EvidenceApplicationService;
import io.github.ismoyuan.opspilot.application.evidence.LinkEvidenceCommand;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisApplicationService;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisStatusRecorder;
import io.github.ismoyuan.opspilot.application.hypothesis.ProposeHypothesisCommand;
import io.github.ismoyuan.opspilot.application.remediation.RemediationPlanSuperseder;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.diagnosis.Diagnosis;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisDraft;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证 Diagnosis 创建事务（08 TASK-025～026、01 §11、§19～§20、04 §32～§36）：冻结引用、版本递增、run_no、
 * INVESTIGATING → DIAGNOSED 与 DIAGNOSIS_CREATED 同一次提交；旧 run、非法主假设/引用、非调查阶段拒绝且零写入；
 * 时间线或 Plan 失效步骤失败时整体回滚；持久层没有修改或删除 Diagnosis 的入口。
 */
@SpringBootTest
@Testcontainers
@Import({
    DiagnosisApplicationService.class,
    EvidenceApplicationService.class,
    HypothesisApplicationService.class,
    HypothesisStatusRecorder.class,
    DiagnosisIntegrationTest.FixedClock.class
})
class DiagnosisIntegrationTest {

    static final Instant NOW = Instant.parse("2026-09-27T08:00:00.250Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    DiagnosisApplicationService service;

    @Autowired
    EvidenceApplicationService evidence;

    @Autowired
    HypothesisApplicationService hypotheses;

    @MockitoSpyBean
    TimelineRepository timeline;

    @MockitoSpyBean
    RemediationPlanSuperseder plans;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    InvestigationFixture fixture;
    long primary;
    long other;
    long supportsPrimary;
    long contextPrimary;
    long supportsOther;

    @BeforeEach
    void seed() {
        fixture = InvestigationFixture.reset(jdbc);
        long o1 = fixture.observation(fixture.incidentId(), fixture.investigationId(), "inv-1");
        long o2 = fixture.observation(fixture.incidentId(), fixture.investigationId(), "inv-2");
        primary = propose("Statistics Consumer 已停止");
        other = propose("Producer 已停止");
        supportsPrimary = link(o1, primary, EvidenceRelation.SUPPORTS);
        contextPrimary = link(o2, primary, EvidenceRelation.CONTEXT);
        supportsOther = link(o1, other, EvidenceRelation.SUPPORTS);
    }

    /** 一次提交：Diagnosis v1（run 1）、冻结引用、Plan 失效调用、INVESTIGATING → DIAGNOSED、DIAGNOSIS_CREATED。 */
    @Test
    void createsDiagnosisWithFrozenReferencesAndDiagnosedIncidentInOneCommit() {
        long versionBefore = incidentVersion();

        Diagnosis created = service.createDiagnosis(command(
                1,
                DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED,
                primary,
                List.of(contextPrimary, supportsPrimary),
                TerminationReason.AGENT_COMPLETED));

        assertThat(created.versionNo()).isOne();
        assertThat(created.runNo()).isOne();
        assertThat(created.investigationId()).isEqualTo(fixture.investigationId());
        assertThat(created.evidenceIds()).containsExactly(supportsPrimary, contextPrimary);
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(refs(created.id())).containsExactly(supportsPrimary, contextPrimary);
        assertThat(incident()).containsEntry("status", "DIAGNOSED").containsEntry("lock_version", versionBefore + 1);
        verify(plans).supersedeUnexecutedPlans(fixture.incidentId(), created.id(), NOW);

        Map<String, Object> event = fixture.onlyEvent("DIAGNOSIS_CREATED");
        assertThat(event).containsEntry("actor_type", "AI_RUNTIME").containsEntry("summary", "形成诊断 v1：已确定主要原因");
        assertThat(fixture.payload(event, "$.diagnosisId")).isEqualTo(String.valueOf(created.id()));
        assertThat(fixture.payload(event, "$.evidenceIds"))
                .isEqualTo("[" + supportsPrimary + ", " + contextPrimary + "]");
        assertThat(fixture.payload(event, "$.terminationReason")).isEqualTo("\"AGENT_COMPLETED\"");
    }

    /** 01 §19、DB-INV-006：新 run 产生 v2，v1 的 run_no 与冻结引用不因后来新增的 Evidence 改变。 */
    @Test
    void laterRunsAddVersionsWithoutChangingEarlierOnes() {
        Diagnosis v1 = service.createDiagnosis(command(
                1,
                DiagnosisConclusionType.POSSIBLE_CAUSE,
                primary,
                List.of(supportsPrimary),
                TerminationReason.USER_STOPPED));
        // 模拟 Continue 进入 run 2（Start/Continue 事务已由 B02 验证）
        jdbc.update("UPDATE incident SET status = 'INVESTIGATING' WHERE id = ?", fixture.incidentId());
        jdbc.update("UPDATE investigation SET current_run_no = 2 WHERE id = ?", fixture.investigationId());
        long o3 = fixture.observation(fixture.incidentId(), fixture.investigationId(), "inv-3");
        long laterSupport = link(o3, primary, EvidenceRelation.REFUTES);

        Diagnosis v2 = service.createDiagnosis(command(
                2,
                DiagnosisConclusionType.UNDETERMINED,
                null,
                List.of(laterSupport),
                TerminationReason.CAPABILITY_BUDGET_EXHAUSTED));

        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.runNo()).isEqualTo(2);
        assertThat(v2.primaryHypothesisId()).isNull();
        assertThat(refs(v1.id())).containsExactly(supportsPrimary);
        assertThat(jdbc.queryForObject("SELECT run_no FROM diagnosis WHERE id = ?", Integer.class, v1.id()))
                .isOne();
        assertThat(fixture.events("DIAGNOSIS_CREATED"))
                .extracting(e -> e.get("actor_type"))
                .containsExactly("SYSTEM", "SYSTEM");
    }

    /**
     * 01 §23、04 §76、08 TASK-062：新 Diagnosis 在同一事务内使本 Incident 的未执行（ACTIVE）Plan 失效；已执行、已取消的 Plan 与其他
     * Incident 的方案不变。创建事务后续步骤失败时整体回滚，ACTIVE Plan 保持原状。
     */
    @Test
    void aNewDiagnosisSupersedesOnlyThisIncidentsUnexecutedPlans() {
        Diagnosis v1 = service.createDiagnosis(
                command(1, DiagnosisConclusionType.UNDETERMINED, null, List.of(), TerminationReason.USER_STOPPED));
        long active = planOn(fixture.incidentId(), v1.id(), "ACTIVE");
        long executed = planOn(fixture.incidentId(), v1.id(), "EXECUTED");
        long cancelled = planOn(fixture.incidentId(), v1.id(), "CANCELLED");
        jdbc.update(
                "INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, summary, impact_summary,"
                        + " created_at) VALUES (?, 1, 1, 'UNDETERMINED', 'S', 'I', UTC_TIMESTAMP(3))",
                fixture.otherInvestigationId());
        long otherIncidents = planOn(
                fixture.otherIncidentId(),
                jdbc.queryForObject(
                        "SELECT id FROM diagnosis WHERE investigation_id = ?",
                        Long.class,
                        fixture.otherInvestigationId()),
                "ACTIVE");
        jdbc.update("UPDATE incident SET status = 'INVESTIGATING' WHERE id = ?", fixture.incidentId());
        jdbc.update("UPDATE investigation SET current_run_no = 2 WHERE id = ?", fixture.investigationId());

        doThrow(new IllegalStateException("timeline down")).when(timeline).append(any());
        assertThatThrownBy(() -> service.createDiagnosis(command(
                        2, DiagnosisConclusionType.UNDETERMINED, null, List.of(), TerminationReason.USER_STOPPED)))
                .isInstanceOf(IllegalStateException.class);
        doCallRealMethod().when(timeline).append(any());
        assertThat(planStatus(active)).isEqualTo("ACTIVE");

        service.createDiagnosis(
                command(2, DiagnosisConclusionType.UNDETERMINED, null, List.of(), TerminationReason.USER_STOPPED));

        assertThat(List.of(planStatus(active), planStatus(executed), planStatus(cancelled), planStatus(otherIncidents)))
                .containsExactly("SUPERSEDED", "EXECUTED", "CANCELLED", "ACTIVE");
    }

    /**
     * 不同调查的 Diagnosis 创建互不阻塞（TASK-026 修复，TASK-016 修复冒烟发现的死锁）：A 的创建已写入但未提交时，B 调查的创建不必
     * 等它提交即可完成，两者各得 v1。原 INSERT … SELECT MAX 在 uk_diagnosis_investigation_version 上留下间隙锁，另一调查的插入须等待
     * 其提交，两者并发时互相等待成死锁。
     */
    @Test
    void diagnosesOfDifferentInvestigationsDoNotBlockEachOther() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        // 外层事务未提交：创建加入该事务，Diagnosis 已插入但仍持有锁
        CompletableFuture<Diagnosis> first =
                CompletableFuture.supplyAsync(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    Diagnosis created = service.createDiagnosis(command(
                            1, DiagnosisConclusionType.UNDETERMINED, null, List.of(), TerminationReason.USER_STOPPED));
                    written.countDown();
                    await(commit);
                    return created;
                }));
        assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Diagnosis> other =
                CompletableFuture.supplyAsync(() -> service.createDiagnosis(new CreateDiagnosisCommand(
                        fixture.otherIncidentId(),
                        1,
                        new DiagnosisDraft(
                                DiagnosisConclusionType.UNDETERMINED, null, "本轮调查已到时间上限。", "统计数据延迟更新", List.of()),
                        TerminationReason.INVESTIGATION_TIMEOUT)));
        try {
            assertThat(other.get(5, TimeUnit.SECONDS).versionNo()).isOne();
        } finally {
            commit.countDown();
        }

        assertThat(first.get(5, TimeUnit.SECONDS).versionNo()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis WHERE version_no = 1", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void invalidDraftsAreRejectedWithoutWrites() {
        long foreignObservation = fixture.observation(fixture.otherIncidentId(), fixture.otherInvestigationId(), "o-x");
        long foreignEvidence = evidence.createEvidenceLink(new LinkEvidenceCommand(
                        fixture.otherIncidentId(),
                        foreignObservation,
                        fixture.otherInvestigationHypothesis(),
                        EvidenceRelation.SUPPORTS,
                        "他案证据",
                        null))
                .evidence()
                .id();
        Map<String, Object> before = incident();
        int events = fixture.eventCount();

        assertRejected(
                command(2, DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED, primary, List.of(supportsPrimary), null),
                ErrorCode.STALE_RUN_RESULT,
                null);
        assertRejected(
                command(1, DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED, primary, List.of(contextPrimary), null),
                ErrorCode.DIAGNOSIS_INVARIANT_VIOLATION,
                "SUPPORTING_EVIDENCE_REQUIRED");
        // 全 Investigation 存在支持证据不等于本 Diagnosis 引用了关联主假设的 SUPPORTS（04 §34）
        assertRejected(
                command(1, DiagnosisConclusionType.POSSIBLE_CAUSE, primary, List.of(supportsOther), null),
                ErrorCode.DIAGNOSIS_INVARIANT_VIOLATION,
                "SUPPORTING_EVIDENCE_REQUIRED");
        assertRejected(
                command(1, DiagnosisConclusionType.UNDETERMINED, null, List.of(foreignEvidence), null),
                ErrorCode.DIAGNOSIS_INVARIANT_VIOLATION,
                "EVIDENCE_NOT_IN_INVESTIGATION");
        assertRejected(
                command(1, DiagnosisConclusionType.UNDETERMINED, null, List.of(999_999L), null),
                ErrorCode.DIAGNOSIS_INVARIANT_VIOLATION,
                "EVIDENCE_NOT_IN_INVESTIGATION");
        assertRejected(
                command(
                        1,
                        DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED,
                        fixture.otherInvestigationHypothesis(),
                        List.of(supportsPrimary),
                        null),
                ErrorCode.DIAGNOSIS_INVARIANT_VIOLATION,
                "PRIMARY_HYPOTHESIS_NOT_IN_INVESTIGATION");
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED' WHERE id = ?", fixture.incidentId());
        assertRejected(
                command(1, DiagnosisConclusionType.UNDETERMINED, null, List.of(), null),
                ErrorCode.INCIDENT_STATE_CONFLICT,
                null);
        jdbc.update("UPDATE incident SET status = 'INVESTIGATING' WHERE id = ?", fixture.incidentId());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_evidence_ref", Integer.class))
                .isZero();
        assertThat(incident()).isEqualTo(before);
        assertThat(fixture.eventCount()).isEqualTo(events);
    }

    /** 同事务：Plan 失效或时间线失败时，Diagnosis、引用与 Incident 迁移都不落库。 */
    @Test
    void failureOfAnyLaterStepRollsBackTheWholeDiagnosis() {
        Map<String, Object> before = incident();
        doThrow(new IllegalStateException("plans down"))
                .when(plans)
                .supersedeUnexecutedPlans(anyLong(), anyLong(), any());
        assertThatThrownBy(() -> service.createDiagnosis(validPrimary())).isInstanceOf(IllegalStateException.class);
        doCallRealMethod().when(plans).supersedeUnexecutedPlans(anyLong(), anyLong(), any());

        doThrow(new IllegalStateException("timeline down")).when(timeline).append(any());
        assertThatThrownBy(() -> service.createDiagnosis(validPrimary())).isInstanceOf(IllegalStateException.class);
        doCallRealMethod().when(timeline).append(any());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_evidence_ref", Integer.class))
                .isZero();
        assertThat(incident()).isEqualTo(before);
        assertThat(fixture.events("DIAGNOSIS_CREATED")).isEmpty();

        // 故障解除后同一草稿仍从 v1 开始
        assertThat(service.createDiagnosis(validPrimary()).versionNo()).isOne();
    }

    /** 端口只有插入；所有 Mapper XML 中都不存在 UPDATE/DELETE diagnosis 或 diagnosis_evidence_ref（04 §32、§36）。 */
    @Test
    void persistenceOffersNoWayToModifyDiagnoses() throws IOException {
        assertThat(Arrays.stream(DiagnosisRepository.class.getDeclaredMethods()).map(Method::getName))
                .containsExactly("insert");
        Pattern modify = Pattern.compile(
                "(?is)\\b(update\\s+diagnosis(_evidence_ref)?\\b|delete\\s+from\\s+diagnosis(_evidence_ref)?\\b)");
        Resource[] mappers =
                new PathMatchingResourcePatternResolver().getResources("classpath*:io/github/**/*Mapper.xml");
        assertThat(mappers).isNotEmpty();
        for (Resource mapper : mappers) {
            assertThat(modify.matcher(mapper.getContentAsString(StandardCharsets.UTF_8))
                            .find())
                    .as(mapper.getFilename())
                    .isFalse();
        }
    }

    private CreateDiagnosisCommand validPrimary() {
        return command(
                1,
                DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED,
                primary,
                List.of(supportsPrimary),
                TerminationReason.AGENT_COMPLETED);
    }

    private CreateDiagnosisCommand command(
            int runNo, DiagnosisConclusionType type, Long primaryId, List<Long> evidenceIds, TerminationReason reason) {
        return new CreateDiagnosisCommand(
                fixture.incidentId(),
                runNo,
                new DiagnosisDraft(type, primaryId, "统计消费者已停止，消息持续积压。", "统计数据延迟更新", evidenceIds),
                reason == null ? TerminationReason.AGENT_COMPLETED : reason);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertRejected(CreateDiagnosisCommand command, ErrorCode code, String reason) {
        assertThatThrownBy(() -> service.createDiagnosis(command))
                .isInstanceOfSatisfying(OpsPilotException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(code);
                    if (reason != null) {
                        assertThat(ex.details()).containsEntry("reason", reason);
                    }
                });
    }

    private long propose(String title) {
        return hypotheses
                .proposeHypothesis(new ProposeHypothesisCommand(fixture.incidentId(), title, null))
                .id();
    }

    private long link(long observationId, long hypothesisId, EvidenceRelation relation) {
        return evidence.createEvidenceLink(new LinkEvidenceCommand(
                        fixture.incidentId(), observationId, hypothesisId, relation, relation + " 理由", null))
                .evidence()
                .id();
    }

    private List<Long> refs(long diagnosisId) {
        return jdbc.queryForList(
                "SELECT CAST(evidence_id AS SIGNED) FROM diagnosis_evidence_ref WHERE diagnosis_id = ?"
                        + " ORDER BY evidence_id",
                Long.class,
                diagnosisId);
    }

    private Map<String, Object> incident() {
        return jdbc.queryForMap(
                "SELECT status, CAST(lock_version AS SIGNED) AS lock_version, updated_at FROM incident WHERE id = ?",
                fixture.incidentId());
    }

    private long incidentVersion() {
        return (Long) incident().get("lock_version");
    }

    private long planOn(long incidentId, long diagnosisId, String status) {
        jdbc.update(
                "INSERT INTO remediation_plan (incident_id, diagnosis_id, title, summary, status, created_at,"
                        + " updated_at) VALUES (?, ?, '恢复统计消费', '重新启动统计消费者', ?, UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3))",
                incidentId,
                diagnosisId,
                status);
        return jdbc.queryForObject("SELECT MAX(id) FROM remediation_plan", Long.class);
    }

    private String planStatus(long planId) {
        return jdbc.queryForObject("SELECT status FROM remediation_plan WHERE id = ?", String.class, planId);
    }
}
