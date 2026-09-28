package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.AiCallMetadata;
import io.github.ismoyuan.opspilot.application.ai.InvestigationStepDecision;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ProposeHypothesis;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.StopInvestigationCommand;
import io.github.ismoyuan.opspilot.application.investigation.step.AgentStepRecorder;
import io.github.ismoyuan.opspilot.application.investigation.step.StepAdmission;
import io.github.ismoyuan.opspilot.application.investigation.step.StepAdmissionService;
import io.github.ismoyuan.opspilot.application.investigation.step.StepOutcome;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
import io.github.ismoyuan.opspilot.infrastructure.ai.AiProtocolCodec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证 AgentStep 生命周期与原子准入（08 TASK-038～039、07 §41～§42）：准入登记 RUNNING、step_no 跨 run 单调；
 * 每种拒绝都不建 Step；Stop 与准入在 Incident 行锁上串行（先提交者决定结果）；结果从 RUNNING 条件终结，只对当前 run 维护
 * 连续 AI 失败；迟到输出照常审计但报告非当前；回显不符按 AI_OUTPUT_INVALID 失败。
 */
@SpringBootTest
@Testcontainers
@Import({
    StepAdmissionService.class,
    AgentStepRecorder.class,
    InvestigationApplicationService.class,
    ClockConfiguration.class,
    InvestigationStepIntegrationTest.FixedClock.class
})
class InvestigationStepIntegrationTest {

    static final Instant NOW = Instant.parse("2026-09-28T08:00:00.000Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 可推进的时钟：模拟锁等待期间时间越过本轮截止（B10-R1）。 */
    static final class MovableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired
    StepAdmissionService admission;

    @Autowired
    AgentStepRecorder recorder;

    @Autowired
    InvestigationApplicationService investigations;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    MovableClock clock;

    InvestigationFixture fixture;
    long incident;

    @BeforeEach
    void seed() {
        clock.now.set(NOW);
        fixture = InvestigationFixture.reset(jdbc);
        incident = fixture.incidentId();
        run(1, NOW.minusSeconds(100));
    }

    @Test
    void admissionRegistersRunningStepsNumberedAcrossRuns() {
        run(1, NOW.minusSeconds(450));
        StepAdmission.Admitted first = admitted(1);
        StepAdmission.Admitted second = admitted(1);
        run(2, NOW.minusSeconds(10));
        StepAdmission.Admitted third = admitted(2);

        assertThat(first.step().stepNo()).isOne();
        assertThat(second.step().stepNo()).isEqualTo(2);
        assertThat(third.step().stepNo()).isEqualTo(3);
        assertThat(third.step().runNo()).isEqualTo(2);
        // 本轮剩 30 秒时上限取剩余时间；新一轮取单步超时 60 秒
        assertThat(first.maxWait()).isEqualTo(Duration.ofSeconds(30));
        assertThat(third.maxWait()).isEqualTo(Duration.ofSeconds(60));
        assertThat(step(third.step().id()))
                .containsEntry("status", "RUNNING")
                .containsEntry("run_no", 2L)
                .containsEntry("finished_at", null)
                .containsEntry("started_at", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    /** 准入前拒绝：不建 Step、不改计数，调用方不得发网络请求（08 TASK-039）。 */
    @Test
    void everyRejectionLeavesNoStep() {
        assertRejected(2, StepAdmissionRejection.STALE_RUN);
        update("current_run_capability_count = 12");
        assertRejected(1, StepAdmissionRejection.CAPABILITY_BUDGET_EXHAUSTED);
        update("current_run_capability_count = 0, consecutive_ai_failure_count = 3");
        assertRejected(1, StepAdmissionRejection.AI_FAILURE_THRESHOLD_REACHED);
        update("consecutive_ai_failure_count = 0");
        run(1, NOW.minusSeconds(480));
        assertRejected(1, StepAdmissionRejection.DEADLINE_REACHED);
        run(1, NOW.minusSeconds(100));
        update("stop_requested_at = UTC_TIMESTAMP(3), stop_requested_by = 'demo-user'");
        assertRejected(1, StepAdmissionRejection.STOP_REQUESTED);
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED' WHERE id = ?", incident);
        assertRejected(1, StepAdmissionRejection.NOT_INVESTIGATING);
        assertThat(admission.admit(999_999L, 1))
                .isEqualTo(new StepAdmission.Rejected(StepAdmissionRejection.NOT_INVESTIGATING));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_step_record", Integer.class))
                .isZero();
    }

    /** Stop 事务先持有 Incident 锁：准入等待其提交，随后看到 Stop 而拒绝（07 §42 线性化边界）。 */
    @Test
    void admissionWaitsForAnInFlightStopAndThenRejects() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Void> stop = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM incident WHERE id = ? FOR UPDATE", Long.class, incident);
                    update("stop_requested_at = UTC_TIMESTAMP(3), stop_requested_by = 'demo-user'");
                    locked.countDown();
                    await(commit);
                }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<StepAdmission> admitted = CompletableFuture.supplyAsync(() -> admission.admit(incident, 1));
        Thread.sleep(500);
        assertThat(admitted).isNotDone();

        commit.countDown();
        stop.get(5, TimeUnit.SECONDS);
        assertThat(admitted.get(5, TimeUnit.SECONDS))
                .isEqualTo(new StepAdmission.Rejected(StepAdmissionRejection.STOP_REQUESTED));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_step_record", Integer.class))
                .isZero();
    }

    /**
     * 截止时间以取得 Incident → Investigation 锁之后的时刻判断（B10-R1）：锁等待期间越过本轮截止，返回 DEADLINE_REACHED 且不写 Step。
     */
    @Test
    void deadlineIsJudgedAfterTheLocksAreAcquired() throws Exception {
        run(1, NOW.minusSeconds(479));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM incident WHERE id = ? FOR UPDATE", Long.class, incident);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        // 进入准入时离截止还有 1 秒
        CompletableFuture<StepAdmission> admitted = CompletableFuture.supplyAsync(() -> admission.admit(incident, 1));
        Thread.sleep(300);
        assertThat(admitted).isNotDone();
        // 锁等待期间越过截止
        clock.now.set(NOW.plusSeconds(2));
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);

        assertThat(admitted.get(5, TimeUnit.SECONDS))
                .isEqualTo(new StepAdmission.Rejected(StepAdmissionRejection.DEADLINE_REACHED));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_step_record", Integer.class))
                .isZero();
    }

    /** 准入先提交：已准入的这一步保持 RUNNING 可完成；Stop 之后的下一次准入被拒绝。 */
    @Test
    void stopAfterAdmissionLetsTheAdmittedStepFinishButBlocksTheNext() {
        StepAdmission.Admitted inFlight = admitted(1);
        String key = jdbc.queryForObject("SELECT incident_key FROM incident WHERE id = ?", String.class, incident);
        investigations.stopInvestigation(new StopInvestigationCommand(key, 0, "demo-user"));

        assertRejected(1, StepAdmissionRejection.STOP_REQUESTED);
        StepOutcome outcome = recorder.recordDecision(inFlight.step().id(), decision(inFlight, 1), 120);

        assertThat(outcome).isEqualTo(new StepOutcome(true, true, 0));
        assertThat(step(inFlight.step().id())).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void legalOutputIsRecordedAndResetsConsecutiveFailures() {
        update("consecutive_ai_failure_count = 2");
        StepAdmission.Admitted admitted = admitted(1);
        InvestigationStepDecision decision = new InvestigationStepDecision(
                proposal(admitted, 1),
                new AiCallMetadata("openai-compatible", "demo-model", "investigation-v1", 1200, 85));

        StepOutcome outcome = recorder.recordDecision(admitted.step().id(), decision, 842);

        assertThat(outcome).isEqualTo(new StepOutcome(true, false, 0));
        assertThat(step(admitted.step().id()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("intent_type", "PROPOSE_HYPOTHESIS")
                .containsEntry("model_provider", "openai-compatible")
                .containsEntry("model_name", "demo-model")
                .containsEntry("prompt_template_version", "investigation-v1")
                .containsEntry("prompt_tokens", 1200L)
                .containsEntry("completion_tokens", 85L)
                .containsEntry("latency_ms", 842L)
                .containsEntry("output_schema_version", 1L)
                .containsEntry("error_code", null)
                .containsEntry("finished_at", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        String payload = jdbc.queryForObject(
                "SELECT output_payload FROM agent_step_record WHERE id = ?",
                String.class,
                admitted.step().id());
        assertThat(new AiProtocolCodec().decode(payload, InvestigationStepResponse.class))
                .isEqualTo(decision.response());
        assertThat(failures()).isZero();
    }

    /** 连接失败、超时、输出非法计入当前 run；其他错误只记录不计数（02 §28）。 */
    @Test
    void aiFailuresCountButOtherErrorsDoNot() {
        long timeout = admitted(1).step().id();
        assertThat(recorder.recordFailure(
                        timeout, ErrorCode.AI_RUNTIME_TIMEOUT, "AI runtime did not answer in time", 60_000))
                .isEqualTo(new StepOutcome(true, false, 1));
        long internal = admitted(1).step().id();
        assertThat(recorder.recordFailure(internal, ErrorCode.INTERNAL_ERROR, "AI runtime answered HTTP 422", 20))
                .isEqualTo(new StepOutcome(true, false, 1));
        StepAdmission.Admitted mismatched = admitted(1);
        InvestigationStepDecision wrongEcho = new InvestigationStepDecision(
                new InvestigationStepResponse.ProposeHypothesisStep(
                        1, 1, mismatched.step().id() + 100, new ProposeHypothesis("Redis 异常", null)),
                AiCallMetadata.UNKNOWN);
        assertThat(recorder.recordDecision(mismatched.step().id(), wrongEcho, 30))
                .isEqualTo(new StepOutcome(true, false, 2));

        assertThat(step(timeout)).containsEntry("status", "FAILED").containsEntry("error_code", "AI_RUNTIME_TIMEOUT");
        assertThat(step(internal)).containsEntry("error_code", "INTERNAL_ERROR");
        assertThat(step(mismatched.step().id()))
                .containsEntry("error_code", "AI_OUTPUT_INVALID")
                .containsEntry("output_payload", null);
        assertThat(failures()).isEqualTo(2);
    }

    /** 迟到输出：旧 run 的结果照常审计，但报告非当前，不改新 run 的连续失败计数（07 §42～§43）。 */
    @Test
    void lateOutputIsAuditedButDoesNotTouchTheNewRun() {
        StepAdmission.Admitted oldRun = admitted(1);
        StepAdmission.Admitted oldRunFailing = admitted(1);
        run(2, NOW.minusSeconds(5));
        update("consecutive_ai_failure_count = 1");

        assertThat(recorder.recordDecision(oldRun.step().id(), decision(oldRun, 1), 90))
                .isEqualTo(new StepOutcome(false, false, 1));
        assertThat(recorder.recordFailure(oldRunFailing.step().id(), ErrorCode.AI_OUTPUT_INVALID, "invalid", 90))
                .isEqualTo(new StepOutcome(false, false, 1));

        assertThat(step(oldRun.step().id()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("run_no", 1L);
        assertThat(step(oldRunFailing.step().id())).containsEntry("status", "FAILED");
        assertThat(failures()).isOne();
    }

    @Test
    void aStepCanOnlyBeFinishedOnce() {
        StepAdmission.Admitted admitted = admitted(1);
        recorder.recordDecision(admitted.step().id(), decision(admitted, 1), 10);
        Map<String, Object> finished = step(admitted.step().id());

        assertThatThrownBy(() -> recorder.recordFailure(admitted.step().id(), ErrorCode.AI_RUNTIME_TIMEOUT, "late", 10))
                .isInstanceOf(IllegalStateException.class);
        assertThat(step(admitted.step().id())).isEqualTo(finished);
        assertThat(failures()).isZero();
    }

    private StepAdmission.Admitted admitted(int runNo) {
        StepAdmission result = admission.admit(incident, runNo);
        assertThat(result).isInstanceOf(StepAdmission.Admitted.class);
        return (StepAdmission.Admitted) result;
    }

    private void assertRejected(int runNo, StepAdmissionRejection reason) {
        assertThat(admission.admit(incident, runNo)).isEqualTo(new StepAdmission.Rejected(reason));
    }

    private static InvestigationStepDecision decision(StepAdmission.Admitted admitted, int runNo) {
        return new InvestigationStepDecision(proposal(admitted, runNo), AiCallMetadata.UNKNOWN);
    }

    private static InvestigationStepResponse proposal(StepAdmission.Admitted admitted, int runNo) {
        return new InvestigationStepResponse.ProposeHypothesisStep(
                1, runNo, admitted.step().id(), new ProposeHypothesis("Statistics Consumer 已停止", null));
    }

    private void run(int runNo, Instant startedAt) {
        jdbc.update(
                "UPDATE investigation SET current_run_no = ?, current_run_started_at = ?, max_duration_seconds = 480,"
                        + " agent_step_timeout_seconds = 60, max_capability_calls = 12,"
                        + " max_consecutive_ai_failures = 3 WHERE id = ?",
                runNo,
                LocalDateTime.ofInstant(startedAt, ZoneOffset.UTC),
                fixture.investigationId());
    }

    private void update(String assignments) {
        jdbc.update("UPDATE investigation SET " + assignments + " WHERE id = ?", fixture.investigationId());
    }

    private int failures() {
        return jdbc.queryForObject(
                "SELECT consecutive_ai_failure_count FROM investigation WHERE id = ?",
                Integer.class,
                fixture.investigationId());
    }

    private Map<String, Object> step(long id) {
        return jdbc.queryForMap(
                "SELECT status, CAST(run_no AS SIGNED) AS run_no, intent_type, model_provider, model_name,"
                        + " prompt_template_version, CAST(prompt_tokens AS SIGNED) AS prompt_tokens,"
                        + " CAST(completion_tokens AS SIGNED) AS completion_tokens,"
                        + " CAST(latency_ms AS SIGNED) AS latency_ms,"
                        + " CAST(output_schema_version AS SIGNED) AS output_schema_version, output_payload, error_code,"
                        + " started_at, finished_at FROM agent_step_record WHERE id = ?",
                id);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
