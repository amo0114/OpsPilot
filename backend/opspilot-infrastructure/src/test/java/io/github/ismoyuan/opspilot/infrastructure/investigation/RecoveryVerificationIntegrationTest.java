package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.QueueInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ServiceInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAdmissionService;
import io.github.ismoyuan.opspilot.application.capability.CapabilityExecutionService;
import io.github.ismoyuan.opspilot.application.capability.CapabilityInvoker;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.capability.CapabilityResultRecorder;
import io.github.ismoyuan.opspilot.application.capability.DuplicateGuard;
import io.github.ismoyuan.opspilot.application.capability.InvocationOutcome;
import io.github.ismoyuan.opspilot.application.capability.ObserveResultPipeline;
import io.github.ismoyuan.opspilot.application.capability.extract.ObservationExtractor;
import io.github.ismoyuan.opspilot.application.capability.provider.ObserveProvider;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderCapabilityInvoker;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.CapabilityResult;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.HealthStatus;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWorkSource;
import io.github.ismoyuan.opspilot.application.dispatch.StartupRecoveryCoordinator;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryCriterionV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryOutcome;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyActivationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyCriteriaV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySelector;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySnapshotV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyValidator;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPredicateV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySample;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySampleInterruptionRecorder;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySampler;
import io.github.ismoyuan.opspilot.application.recovery.RecoverySamplingV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationResultV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationService;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.infrastructure.dispatch.InProcessWorkDispatcher;
import io.github.ismoyuan.opspilot.infrastructure.dispatch.SingleFlightRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 08 TASK-078～079：真实 MySQL 上的恢复采样与 Verification Runner。策略经正式激活服务启用，快照由正式选择器按此刻配置生成，
 * Verification 行按 TASK-080 将要写入的形态直接插入（创建本身不属本批）。Provider 调用由 Invoker 替身按用例给出类型化结果，
 * 成功结果仍经正式结果管线与结果事务落账（真实 Observation）。
 */
@SpringBootTest
@Testcontainers
@Import({
    RecoveryVerificationService.class,
    RecoverySampler.class,
    RecoveryPolicySelector.class,
    RecoveryPolicyValidator.class,
    RecoveryPolicyActivationService.class,
    CapabilityExecutionService.class,
    CapabilityAdmissionService.class,
    DuplicateGuard.class,
    CapabilityResultRecorder.class,
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    Sanitizer.class,
    ObservationExtractor.class,
    ObserveResultPipeline.class,
    InvestigationApplicationService.class,
    RecoverySampleInterruptionRecorder.class,
    ClockConfiguration.class
})
class RecoveryVerificationIntegrationTest {

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
    RecoveryVerificationService runner;

    @Autowired
    RecoverySampler sampler;

    @Autowired
    RecoverySampleInterruptionRecorder interruptions;

    @Autowired
    List<DispatchableWorkSource> workSources;

    @Autowired
    RecoveryPolicyActivationService recoveryPolicies;

    @Autowired
    RecoveryPolicySelector selector;

    @Autowired
    ManagedResourceRepository resources;

    @Autowired
    ObserveResultPipeline pipeline;

    @Autowired
    SchemaCodecRegistry codecs;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    CapabilityInvoker invoker;

    /** 结果迁移派发调查的替身（TASK-082）：记录派发时独立连接看到的调查轮号，证明派发在提交之后。 */
    @MockitoBean
    WorkDispatcher dispatcher;

    final List<String> runSeenAtDispatch = new CopyOnWriteArrayList<>();

    RemediationFixture seeded;

    /** 按能力排队的替身结果：CapabilityResult 为成功，ErrorCode 为调用失败；最后一个重复使用。 */
    final Map<String, Deque<Object>> answers = new java.util.HashMap<>();

    /** 每次 Provider 调用时是否处在事务中、调用开始时刻。 */
    final List<Boolean> transactionActiveDuringProvider = new CopyOnWriteArrayList<>();

    @BeforeEach
    void seed() {
        seeded = RemediationFixture.seed(jdbc);
        runSeenAtDispatch.clear();
        doAnswer(invocation -> {
                    try (Connection other = DriverManager.getConnection(
                                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                            var query = other.prepareStatement(
                                    "SELECT current_run_no FROM investigation WHERE incident_id = ?")) {
                        query.setLong(1, invocation.getArgument(0));
                        try (var rs = query.executeQuery()) {
                            rs.next();
                            runSeenAtDispatch.add(invocation.getArgument(1) + "/" + rs.getInt(1));
                        }
                    }
                    return null;
                })
                .when(dispatcher)
                .dispatchInvestigation(anyLong(), anyInt());
        answers.clear();
        transactionActiveDuringProvider.clear();
        doAnswer(invocation -> {
                    AdmittedInvocation admitted = invocation.getArgument(0);
                    transactionActiveDuringProvider.add(TransactionSynchronizationManager.isActualTransactionActive());
                    Deque<Object> queue =
                            answers.get(admitted.definition().key().key());
                    Object answer = queue.size() > 1 ? queue.poll() : queue.peek();
                    if (answer instanceof ErrorCode code) {
                        return new InvocationOutcome.Failed(code, "provider failed");
                    }
                    return pipeline.succeeded(
                            admitted.definition(),
                            admitted.incidentId(),
                            admitted.invocationId(),
                            (CapabilityResult) answer,
                            null,
                            Instant.now());
                })
                .when(invoker)
                .invoke(any());
    }

    // ---------------------------------------------------------------- TASK-078/079 主路径

    /**
     * 全部 required TRUE → PASSED：按快照顺序采样，每个样本是带 verificationId＋criterionKey＋sampleIndex 的独立 Invocation（无调查、
     * 无 run），产生归属 Verification 的 Observation；第 2 个样本不早于上一样本完成＋间隔；Provider 调用不在事务内；调查预算与计数不变；
     * 结果载荷逐项记录样本引用；Incident 仍 VERIFYING（迁移属 TASK-082）。
     */
    @Test
    void allRequiredChecksTrueArePassedWithTraceableSamples() {
        long verificationId = verification(policy(1));
        Map<String, Object> investigationBefore = investigationRow();
        answer("queue.inspect", queue(0L, 0));
        answer("service.inspect", service(RuntimeState.RUNNING));

        runner.runRecoveryVerification(verificationId);

        assertThat(verificationRow(verificationId))
                .containsEntry("status", "PASSED")
                .containsEntry("has_started", "YES");
        assertThat(transactionActiveDuringProvider).containsExactly(false, false, false);
        List<Map<String, Object>> samples = samples(verificationId);
        assertThat(samples)
                .extracting(row -> row.get("criterion_key") + "#" + row.get("sample_index") + "/" + row.get("status"))
                .containsExactly(
                        "consumer-running#1/SUCCEEDED",
                        "consumer-running#2/SUCCEEDED",
                        "stream-lag-drained#1/SUCCEEDED");
        assertThat(samples).allSatisfy(row -> {
            assertThat(row.get("investigation_id")).isNull();
            assertThat(row.get("run_no")).isNull();
            assertThat(row.get("observations")).isEqualTo(1L);
        });
        assertThat(Duration.between(
                        instant(samples.get(0).get("finished_at")),
                        instant(samples.get(1).get("started_at"))))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(investigationRow()).isEqualTo(investigationBefore);
        RecoveryVerificationResultV1 result = result(verificationId);
        assertThat(result.overallResult()).isEqualTo(RecoveryOutcome.PASSED);
        assertThat(result.checks())
                .extracting(RecoveryVerificationResultV1.Check::criterionKey)
                .containsExactly("stream-lag-drained", "consumer-running");
        assertThat(result.checks().get(1).samples())
                .extracting(
                        RecoveryVerificationResultV1.Sample::sampleIndex, RecoveryVerificationResultV1.Sample::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, RecoverySample.Status.SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(2, RecoverySample.Status.SUCCEEDED));
        assertThat(verificationEvents())
                .containsExactly("RECOVERY_VERIFICATION_STARTED/-", "RECOVERY_VERIFICATION_PASSED/PASSED");
        // TASK-082：同一终态事务 VERIFYING → RESOLVED 并写 resolved_at 与 INCIDENT_RESOLVED；不开启新 run
        assertThat(incident()).isEqualTo("RESOLVED/8/resolved");
        assertThat(lastEvent()).isEqualTo("INCIDENT_RESOLVED/SYSTEM");
        verify(dispatcher, never()).dispatchInvestigation(anyLong(), anyInt());
    }

    /** ACC-FINAL-09 TRUE+UNKNOWN：lag 为空（不当 0）而服务运行 → INCONCLUSIVE，摘要说明原因。 */
    @Test
    void trueAndUnknownIsInconclusive() {
        long verificationId = verification(policy(1));
        answer("queue.inspect", queue(null, 0));
        answer("service.inspect", service(RuntimeState.RUNNING));

        runner.runRecoveryVerification(verificationId);

        assertThat(verificationRow(verificationId))
                .containsEntry("status", "INCONCLUSIVE")
                .containsEntry("result_summary", "恢复验证无法确认：积压达标（stream-lag-drained）VALUE_UNKNOWN");
        assertThat(checkResults(verificationId))
                .containsExactly("stream-lag-drained/UNKNOWN/VALUE_UNKNOWN", "consumer-running/TRUE/SATISFIED");
        // TASK-082：INCONCLUSIVE → DIAGNOSED，不自动开启新 run，用户可再次请求验证
        assertThat(incident()).isEqualTo("DIAGNOSED/8/-");
        assertThat(investigationRow().get("current_run_no").toString()).isEqualTo("1");
        verify(dispatcher, never()).dispatchInvestigation(anyLong(), anyInt());
    }

    /** ACC-FINAL-09 FALSE+UNKNOWN：UNKNOWN 不短路，继续执行后续检查以发现明确 FALSE → FAILED。 */
    @Test
    void unknownDoesNotShortCircuitAndALaterFalseDecides() {
        long verificationId = verification(policy(1));
        answer("queue.inspect", ErrorCode.TIMEOUT);
        answer("service.inspect", service(RuntimeState.STOPPED));

        runner.runRecoveryVerification(verificationId);

        assertThat(verificationRow(verificationId)).containsEntry("status", "FAILED");
        assertThat(checkResults(verificationId))
                .containsExactly("stream-lag-drained/UNKNOWN/SAMPLE_FAILED", "consumer-running/FALSE/VIOLATED");
        assertThat(samples(verificationId))
                .extracting(row -> row.get("criterion_key") + "#" + row.get("sample_index") + "/" + row.get("status"))
                .as("明确 FALSE 在第 1 个样本出现即短路，不再采第 2 个")
                .containsExactly("consumer-running#1/SUCCEEDED", "stream-lag-drained#1/FAILED");
        assertThat(verificationEvents()).last().isEqualTo("RECOVERY_VERIFICATION_FAILED/FAILED");
        // TASK-082：FAILED → INVESTIGATING，同一 Investigation 进入第 2 轮（统一新 run 逻辑，系统发起），提交后才派发；不重放 CHANGE
        assertThat(incident()).isEqualTo("INVESTIGATING/8/-");
        assertThat(investigationRow().get("current_run_no").toString()).isEqualTo("2");
        assertThat(lastEvent()).isEqualTo("INVESTIGATION_STARTED/SYSTEM");
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'$.source' FROM incident_timeline_event ORDER BY id DESC LIMIT 1",
                        String.class))
                .isEqualTo("VERIFICATION_FAILED");
        assertThat(runSeenAtDispatch).containsExactly("2/2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM action_execution", Long.class))
                .isZero();
    }

    /** required 明确 FALSE 可以短路：后续检查不执行，如实记 UNKNOWN / NOT_EXECUTED，没有样本。 */
    @Test
    void aRequiredFalseShortCircuitsTheRemainingChecks() {
        long verificationId = verification(policy(1));
        answer("queue.inspect", queue(100L, 0));
        answer("service.inspect", service(RuntimeState.RUNNING));

        runner.runRecoveryVerification(verificationId);

        assertThat(verificationRow(verificationId))
                .containsEntry("status", "FAILED")
                .containsEntry("result_summary", "恢复验证未通过：积压达标（stream-lag-drained）明确不满足");
        assertThat(checkResults(verificationId))
                .containsExactly("stream-lag-drained/FALSE/VIOLATED", "consumer-running/UNKNOWN/NOT_EXECUTED");
        assertThat(samples(verificationId)).hasSize(1);
    }

    /** ACC-FINAL-12 在 Runner 上：lag 已 0 但 pending 超过健康范围 → FAILED，不得通过。 */
    @Test
    void aDrainedLagWithUnhealthyPendingFails() {
        long verificationId = verification(policy(
                1,
                new RecoveryCriterionV1.QueueInspect(
                        "stream-pending-healthy",
                        "已投递未确认积压保持健康",
                        "statistics-stream",
                        new QueueInspectArgumentsV1(),
                        new RecoverySamplingV1(1, 0, null),
                        new RecoveryPredicateV1.NumericCompare(
                                "pendingCount", RecoveryPredicateV1.ComparisonOperator.LTE, 20.0),
                        true)));
        answer("queue.inspect", queue(0L, 200));
        answer("service.inspect", service(RuntimeState.RUNNING));

        runner.runRecoveryVerification(verificationId);

        assertThat(checkResults(verificationId))
                .startsWith("stream-lag-drained/TRUE/SATISFIED", "stream-pending-healthy/FALSE/VIOLATED");
        assertThat(verificationRow(verificationId)).containsEntry("status", "FAILED");
    }

    // ---------------------------------------------------------------- 样本身份、时间与期限（ACC-FINAL-10 的采样部分）

    /**
     * 身份来自持久化槽位：已失败的第 1 槽不重试，只采第 2 槽；结果为 UNKNOWN / SAMPLE_FAILED → INCONCLUSIVE；deadline 与原失败记录
     * 不被改写。
     */
    @Test
    void failedSlotsAreNeverRetriedAndProgressComesFromPersistedSlots() {
        long verificationId = verification(policy(1));
        jdbc.update(
                "UPDATE recovery_verification SET status = 'RUNNING', started_at = UTC_TIMESTAMP(3) WHERE id = ?",
                verificationId);
        long failedSlot = sampleRow(verificationId, "consumer-running", 1, "FAILED");
        Object deadline = verificationRow(verificationId).get("deadline_at");
        answer("queue.inspect", queue(0L, 0));
        answer("service.inspect", service(RuntimeState.RUNNING));

        runner.runRecoveryVerification(verificationId);

        assertThat(samples(verificationId))
                .extracting(row -> row.get("criterion_key") + "#" + row.get("sample_index") + "/" + row.get("id"))
                .contains("consumer-running#1/" + failedSlot)
                .hasSize(3);
        assertThat(checkResults(verificationId)).contains("consumer-running/UNKNOWN/SAMPLE_FAILED");
        assertThat(verificationRow(verificationId))
                .containsEntry("status", "INCONCLUSIVE")
                .containsEntry("deadline_at", deadline);
    }

    /** 冻结的 deadline 到达后不再准入样本，剩余样本如实不足；已过期的 PENDING 不开始、直接按矩阵收束，deadline 不刷新。 */
    @Test
    void theFrozenDeadlineStopsSamplingAndIsNeverRefreshed() {
        long verificationId = verification(policy(2));
        jdbc.update(
                "UPDATE recovery_verification SET deadline_at = UTC_TIMESTAMP(3) + INTERVAL 1500000 MICROSECOND"
                        + " WHERE id = ?",
                verificationId);
        answer("queue.inspect", queue(0L, 0));
        answer("service.inspect", service(RuntimeState.RUNNING));

        runner.runRecoveryVerification(verificationId);

        assertThat(checkResults(verificationId)).contains("consumer-running/UNKNOWN/INSUFFICIENT_SAMPLES");
        assertThat(samples(verificationId))
                .filteredOn(row -> "consumer-running".equals(row.get("criterion_key")))
                .hasSize(1);
        assertThat(verificationRow(verificationId)).containsEntry("status", "INCONCLUSIVE");

        seed();
        clearInvocations(invoker);
        long expired = verification(policy(1));
        jdbc.update("UPDATE recovery_verification SET deadline_at = created_at WHERE id = ?", expired);
        Object deadline = verificationRow(expired).get("deadline_at");

        runner.runRecoveryVerification(expired);

        assertThat(verificationRow(expired))
                .containsEntry("status", "INCONCLUSIVE")
                .containsEntry("has_started", "NO")
                .containsEntry("deadline_at", deadline);
        assertThat(samples(expired)).isEmpty();
        verify(invoker, never()).invoke(any());
    }

    /** 运行时仍复核访问：批准后停用 service.inspect 绑定 → 不登记调用、不调用 Provider，该项 UNKNOWN / NOT_ADMITTED。 */
    @Test
    void aRevokedCapabilityIsNotAdmittedAtRuntime() {
        long verificationId = verification(policy(1));
        jdbc.update(
                "UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ? AND capability_key = 'service.inspect'",
                seeded.consumer());
        answer("queue.inspect", queue(0L, 0));

        runner.runRecoveryVerification(verificationId);

        assertThat(checkResults(verificationId))
                .containsExactly("stream-lag-drained/TRUE/SATISFIED", "consumer-running/UNKNOWN/NOT_ADMITTED");
        assertThat(samples(verificationId)).hasSize(1);
        assertThat(verificationRow(verificationId)).containsEntry("status", "INCONCLUSIVE");
    }

    /** 等待被中断：保持 RUNNING、已采样本保留；再次运行从持久化槽位继续（第 2 槽），终态只写一次，再运行不改写。 */
    @Test
    void anInterruptedRunResumesFromPersistedSlotsAndTheResultIsWrittenOnce() throws Exception {
        long verificationId = verification(policy(3));
        answer("queue.inspect", queue(0L, 0));
        answer("service.inspect", service(RuntimeState.RUNNING));
        CountDownLatch sampled = new CountDownLatch(2);
        doAnswer(invocation -> {
                    AdmittedInvocation admitted = invocation.getArgument(0);
                    Object answer =
                            answers.get(admitted.definition().key().key()).peek();
                    sampled.countDown();
                    return pipeline.succeeded(
                            admitted.definition(),
                            admitted.incidentId(),
                            admitted.invocationId(),
                            (CapabilityResult) answer,
                            null,
                            Instant.now());
                })
                .when(invoker)
                .invoke(any());
        Thread worker = new Thread(() -> runner.runRecoveryVerification(verificationId));
        worker.start();
        assertThat(sampled.await(30, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);
        worker.interrupt();
        worker.join(10_000);

        assertThat(verificationRow(verificationId)).containsEntry("status", "RUNNING");
        assertThat(samples(verificationId)).hasSize(2);

        runner.runRecoveryVerification(verificationId);
        String payload = verificationRow(verificationId).get("payload").toString();
        runner.runRecoveryVerification(verificationId);

        assertThat(samples(verificationId))
                .extracting(row -> row.get("criterion_key") + "#" + row.get("sample_index"))
                .containsExactly("consumer-running#1", "consumer-running#2", "stream-lag-drained#1");
        assertThat(verificationRow(verificationId))
                .containsEntry("status", "PASSED")
                .containsEntry("payload", payload);
        assertThat(verificationEvents())
                .containsExactly("RECOVERY_VERIFICATION_STARTED/-", "RECOVERY_VERIFICATION_PASSED/PASSED");
    }

    /** 两个 Runner 并发：同一槽位只有一个调用（唯一身份），终态只有一个。 */
    @Test
    void concurrentRunnersNeverDuplicateASlot() throws Exception {
        long verificationId = verification(policy(1));
        answer("queue.inspect", queue(0L, 0));
        answer("service.inspect", service(RuntimeState.RUNNING));
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> runners = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            runners.add(CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
                runner.runRecoveryVerification(verificationId);
            }));
        }
        start.countDown();
        for (var call : runners) {
            call.get(60, TimeUnit.SECONDS);
        }
        runner.runRecoveryVerification(verificationId);

        assertThat(samples(verificationId))
                .extracting(row -> row.get("criterion_key") + "#" + row.get("sample_index"))
                .doesNotHaveDuplicates()
                .hasSize(3);
        assertThat(verificationRow(verificationId)).containsEntry("status", "PASSED");
        assertThat(verificationEvents())
                .filteredOn(event -> event.startsWith("RECOVERY_VERIFICATION_PASSED"))
                .hasSize(1);
    }

    // ---------------------------------------------------------------- B28-R1 回归

    /**
     * B28-R1 [P1]：采样准入在 Incident 行锁之后才建立一致性视图。另一事务持有 Incident 锁期间停用 service.inspect 绑定并提交，释放锁
     * 后该样本必须按撤权后的事实拒绝（NotAdmitted），不登记调用、不调用 Provider。
     */
    @Test
    void aRevocationCommittedDuringTheLockWaitIsSeen() throws Exception {
        long verificationId = verification(policy(1));
        jdbc.update(
                "UPDATE recovery_verification SET status = 'RUNNING', started_at = UTC_TIMESTAMP(3) WHERE id = ?",
                verificationId);
        RecoveryPolicySnapshotV1 snapshot = codecs.decode(
                RecoveryPolicySnapshotV1.SCHEMA_NAME,
                1,
                jdbc.queryForObject(
                        "SELECT CAST(policy_snapshot AS CHAR) FROM recovery_verification WHERE id = ?",
                        String.class,
                        verificationId),
                RecoveryPolicySnapshotV1.class);
        answer("service.inspect", service(RuntimeState.RUNNING));
        CompletableFuture<RecoverySampler.SampleAttempt> attempt;
        try (Connection holder = jdbc.getDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT id FROM incident WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, seeded.investigation().incidentId());
                lock.executeQuery().close();
            }
            attempt = CompletableFuture.supplyAsync(
                    () -> sampler.sample(verificationId, snapshot.criteria().get(1), 1));
            awaitLockWait();
            jdbc.update(
                    "UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ?"
                            + " AND capability_key = 'service.inspect'",
                    seeded.consumer());
            holder.commit();
        }

        assertThat(attempt.get(30, TimeUnit.SECONDS)).isInstanceOf(RecoverySampler.NotAdmitted.class);
        assertThat(samples(verificationId)).isEmpty();
        verify(invoker, never()).invoke(any());
    }

    /**
     * B28-R1 [P1]：冻结 deadline 贯穿外部调用。正式 ProviderCapabilityInvoker＋忽略期限、650 ms 后才返回的 Provider，Verification 只剩
     * 300 ms：Provider 收到的期限不晚于冻结期限，调用在期限处以 TIMEOUT 失败（原调用审计保留），期限后的数据不能支持判定 →
     * INCONCLUSIVE。
     */
    @Test
    void theFrozenDeadlineBoundsTheProviderCall() {
        long verificationId = verification(
                RecoveryPolicyCriteriaV1.of(60, 60, List.of(policy(1).criteria().getFirst())));
        jdbc.update(
                "UPDATE recovery_verification SET deadline_at = UTC_TIMESTAMP(3) + INTERVAL 300000 MICROSECOND"
                        + " WHERE id = ?",
                verificationId);
        Instant frozen = instant(verificationRow(verificationId).get("deadline_at"));
        AtomicReference<Instant> offered = new AtomicReference<>();
        ProviderCapabilityInvoker real = new ProviderCapabilityInvoker(
                List.of(new ObserveProvider() {
                    @Override
                    public CapabilityKey capability() {
                        return CapabilityKey.QUEUE_INSPECT;
                    }

                    @Override
                    public ProviderOutcome fetch(AdmittedInvocation invocation, Instant deadline) {
                        offered.set(deadline);
                        try {
                            Thread.sleep(650);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return new ProviderOutcome.Failed(ErrorCode.TIMEOUT, "timeout");
                        }
                        return new ProviderOutcome.Fetched(queue(0L, 0), null, Instant.now());
                    }
                }),
                pipeline,
                Clock.systemUTC());
        doAnswer(call -> real.invoke(call.getArgument(0))).when(invoker).invoke(any());

        runner.runRecoveryVerification(verificationId);

        assertThat(offered.get()).isBeforeOrEqualTo(frozen);
        assertThat(samples(verificationId))
                .singleElement()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("FAILED"));
        assertThat(checkResults(verificationId)).containsExactly("stream-lag-drained/UNKNOWN/SAMPLE_FAILED");
        assertThat(verificationRow(verificationId)).containsEntry("status", "INCONCLUSIVE");
    }

    /**
     * 第二道防线：即使 Invoker 没有遵守期限、在 deadline 之后才取得并落账了数据，该样本（调用与 Observation 照常保留为审计）也不能
     * 支持 TRUE → UNKNOWN / SAMPLE_AFTER_DEADLINE → INCONCLUSIVE。
     */
    @Test
    void dataObtainedAfterTheFrozenDeadlineCannotPass() {
        long verificationId = verification(
                RecoveryPolicyCriteriaV1.of(60, 60, List.of(policy(1).criteria().getFirst())));
        jdbc.update(
                "UPDATE recovery_verification SET deadline_at = UTC_TIMESTAMP(3) + INTERVAL 300000 MICROSECOND"
                        + " WHERE id = ?",
                verificationId);
        doAnswer(invocation -> {
                    AdmittedInvocation admitted = invocation.getArgument(0);
                    Thread.sleep(650);
                    return pipeline.succeeded(
                            admitted.definition(),
                            admitted.incidentId(),
                            admitted.invocationId(),
                            queue(0L, 0),
                            null,
                            Instant.now());
                })
                .when(invoker)
                .invoke(any());

        runner.runRecoveryVerification(verificationId);

        assertThat(samples(verificationId)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("SUCCEEDED");
            assertThat(row.get("observations")).isEqualTo(1L);
        });
        assertThat(checkResults(verificationId)).containsExactly("stream-lag-drained/UNKNOWN/SAMPLE_AFTER_DEADLINE");
        assertThat(verificationRow(verificationId)).containsEntry("status", "INCONCLUSIVE");
    }

    /** 以 root 查询 InnoDB 事务，直到采样事务处于锁等待。 */
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
        throw new AssertionError("the sample never waited for the incident lock");
    }

    // ---------------------------------------------------------------- TASK-083 启动恢复与补派发

    /** 创建已提交、派发丢失的 PENDING：启动恢复经 Verification 来源唤醒并执行到终态；之后的补派发不再派发终态。 */
    @Test
    void aPendingVerificationWhoseDispatchWasLostRunsAfterStartup() throws Exception {
        long verificationId = verification(policy(1));
        answer("queue.inspect", queue(0L, 0));
        answer("service.inspect", service(RuntimeState.RUNNING));
        StartupRecoveryCoordinator coordinator = coordinator(pool());

        assertThat(coordinator.recoverAfterStartup()).isEqualTo(1);
        awaitStatus(verificationId, "PASSED");

        assertThat(coordinator.redispatchPending()).isZero();
        assertThat(incident()).isEqualTo("RESOLVED/8/resolved");
    }

    /**
     * ACC-FINAL-10：旧进程留下的 RUNNING 样本在启动时标 FAILED/PROCESS_INTERRUPTED（身份与开始时间不变），不在同一槽位重试；已成功
     * 且仍有效的样本复用（不再采 lag）；剩余未准入样本继续；deadline 不刷新；按同一矩阵收束为 INCONCLUSIVE / SAMPLE_FAILED。
     */
    @Test
    void aLeftoverRunningSampleIsMarkedInterruptedAndNeverRetried() throws Exception {
        long verificationId = verification(policy(1));
        jdbc.update(
                "UPDATE recovery_verification SET status = 'RUNNING', started_at = UTC_TIMESTAMP(3) - INTERVAL 20 SECOND"
                        + " WHERE id = ?",
                verificationId);
        long lag = storedSample(
                verificationId,
                "stream-lag-drained",
                "queue.inspect",
                seeded.investigation().streamId(),
                1,
                queue(0L, 0),
                15);
        long interrupted = runningSample(verificationId, "consumer-running", seeded.consumer(), 1, 10);
        Map<String, Object> before = verificationRow(verificationId);
        Object startedBefore = sampleTimes(interrupted).get("started_at");
        answer("service.inspect", service(RuntimeState.RUNNING));

        coordinator(pool()).recoverAfterStartup();
        awaitStatus(verificationId, "INCONCLUSIVE");

        assertThat(jdbc.queryForMap(
                        "SELECT status, error_code, started_at FROM capability_invocation WHERE id = ?", interrupted))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "PROCESS_INTERRUPTED")
                .containsEntry("started_at", startedBefore);
        assertThat(samples(verificationId))
                .extracting(row -> row.get("criterion_key") + "#" + row.get("sample_index") + "/" + row.get("id"))
                .containsExactly(
                        "consumer-running#1/" + interrupted,
                        "consumer-running#2/" + samples(verificationId).get(1).get("id"),
                        "stream-lag-drained#1/" + lag);
        assertThat(checkResults(verificationId))
                .containsExactly("stream-lag-drained/TRUE/SATISFIED", "consumer-running/UNKNOWN/SAMPLE_FAILED");
        assertThat(verificationRow(verificationId)).containsEntry("deadline_at", before.get("deadline_at"));
        assertThat(incident()).isEqualTo("DIAGNOSED/8/-");
    }

    /**
     * 重启时已过冻结 deadline：不再采样，按持久化样本收束；仍有效的明确 FALSE 不被覆盖（FAILED → 新 run），只有 TRUE 而样本不足则
     * INCONCLUSIVE。
     */
    @Test
    void anExpiredVerificationIsSettledFromPersistedSamplesWithoutSampling() throws Exception {
        for (Object[] scenario : new Object[][] {
            {"stopped", "FAILED", "consumer-running/FALSE/VIOLATED", "INVESTIGATING/8/-"},
            {"lag-only", "INCONCLUSIVE", "consumer-running/UNKNOWN/INSUFFICIENT_SAMPLES", "DIAGNOSED/8/-"}
        }) {
            seed();
            clearInvocations(invoker);
            long verificationId = verification(policy(1));
            jdbc.update(
                    "UPDATE recovery_verification SET status = 'RUNNING', started_at = UTC_TIMESTAMP(3) - INTERVAL 70"
                            + " SECOND, created_at = UTC_TIMESTAMP(3) - INTERVAL 70 SECOND,"
                            + " deadline_at = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE id = ?",
                    verificationId);
            storedSample(
                    verificationId,
                    "stream-lag-drained",
                    "queue.inspect",
                    seeded.investigation().streamId(),
                    1,
                    queue(0L, 0),
                    30);
            if (scenario[0].equals("stopped")) {
                storedSample(
                        verificationId,
                        "consumer-running",
                        "service.inspect",
                        seeded.consumer(),
                        1,
                        service(RuntimeState.STOPPED),
                        20);
            }

            coordinator(pool()).recoverAfterStartup();
            awaitStatus(verificationId, (String) scenario[1]);

            assertThat(checkResults(verificationId)).as((String) scenario[0]).contains((String) scenario[2]);
            assertThat(incident()).as((String) scenario[0]).isEqualTo(scenario[3]);
            verify(invoker, never()).invoke(any());
        }
    }

    /** 线程池拒绝不丢弃已提交事实：Verification 保持 PENDING、不采样；下一次周期补派发唤醒并执行。 */
    @Test
    void aRejectedDispatchIsRecoveredByTheNextRescan() throws Exception {
        long verificationId = verification(policy(1));
        answer("queue.inspect", queue(0L, 0));
        answer("service.inspect", service(RuntimeState.RUNNING));
        java.util.concurrent.atomic.AtomicBoolean rejectNext = new java.util.concurrent.atomic.AtomicBoolean(true);
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
        assertThat(verificationRow(verificationId)).containsEntry("status", "PENDING");
        verify(invoker, never()).invoke(any());

        assertThat(coordinator.redispatchPending()).isEqualTo(1);
        awaitStatus(verificationId, "PASSED");
    }

    /** 中断记录只处理界限之前开始的恢复样本调用；之后开始的（本进程）与调查调用都不受影响。 */
    @Test
    void onlySamplesStartedBeforeTheBoundAreMarked() {
        long verificationId = verification(policy(1));
        jdbc.update(
                "UPDATE recovery_verification SET status = 'RUNNING', started_at = UTC_TIMESTAMP(3) WHERE id = ?",
                verificationId);
        long old = runningSample(verificationId, "consumer-running", seeded.consumer(), 1, 30);
        long current = runningSample(
                verificationId, "stream-lag-drained", seeded.investigation().streamId(), 1, 1);

        assertThat(interruptions.recordInterrupted(Instant.now().minusSeconds(10)))
                .isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT status FROM capability_invocation WHERE id = ?", String.class, old))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM capability_invocation WHERE id = ?", String.class, current))
                .isEqualTo("RUNNING");
    }

    private StartupRecoveryCoordinator coordinator(ThreadPoolExecutor pool) {
        InProcessWorkDispatcher real = new InProcessWorkDispatcher(
                pool, new SingleFlightRegistry(), (incidentId, runNo) -> {}, executionId -> {}, runner);
        DispatchableWorkSource verificationWork = () -> workSources.stream()
                .flatMap(source -> source.findDispatchable().stream())
                .filter(DispatchableWork.RecoveryVerification.class::isInstance)
                .toList();
        return new StartupRecoveryCoordinator(
                List.of(verificationWork), List.of(interruptions), real, Clock.systemUTC());
    }

    private static ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    private void awaitStatus(long verificationId, String expected) throws InterruptedException {
        for (int i = 0;
                i < 300 && !expected.equals(verificationRow(verificationId).get("status"));
                i++) {
            Thread.sleep(100);
        }
        assertThat(verificationRow(verificationId)).containsEntry("status", expected);
    }

    /** 已成功的样本槽位：类型化结果按 Codec 编码，开始/完成于 secondsAgo 秒前。 */
    private long storedSample(
            long verificationId,
            String criterionKey,
            String capability,
            long resourceId,
            int sampleIndex,
            CapabilityResult result,
            int secondsAgo) {
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, recovery_verification_id, criterion_key, sample_index,"
                        + " capability_key, managed_resource_id, status, request_schema_name, request_schema_version,"
                        + " request_payload, response_schema_name, response_schema_version, response_payload, started_at,"
                        + " finished_at, duration_ms, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'SUCCEEDED', ?, 1,"
                        + " '{}', ?, ?, ?, UTC_TIMESTAMP(3) - INTERVAL ? SECOND, UTC_TIMESTAMP(3) - INTERVAL ? SECOND, 5,"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                seeded.investigation().incidentId(),
                verificationId,
                criterionKey,
                sampleIndex,
                capability,
                resourceId,
                capability + ".request",
                result.resultSchema().name(),
                result.resultSchema().version(),
                codecs.encode(
                        result.resultSchema().name(), result.resultSchema().version(), result),
                secondsAgo,
                secondsAgo);
        return slotId(verificationId, criterionKey, sampleIndex);
    }

    /** 旧进程留下的 RUNNING 样本槽位，secondsAgo 秒前开始。 */
    private long runningSample(
            long verificationId, String criterionKey, long resourceId, int sampleIndex, int secondsAgo) {
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, recovery_verification_id, criterion_key, sample_index,"
                        + " capability_key, managed_resource_id, status, request_schema_name, request_schema_version,"
                        + " request_payload, started_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'service.inspect', ?,"
                        + " 'RUNNING', 'service.inspect.request', 1, '{}', UTC_TIMESTAMP(3) - INTERVAL ? SECOND,"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                seeded.investigation().incidentId(),
                verificationId,
                criterionKey,
                sampleIndex,
                resourceId,
                secondsAgo);
        return slotId(verificationId, criterionKey, sampleIndex);
    }

    private long slotId(long verificationId, String criterionKey, int sampleIndex) {
        return jdbc.queryForObject(
                "SELECT id FROM capability_invocation WHERE recovery_verification_id = ? AND criterion_key = ?"
                        + " AND sample_index = ?",
                Long.class,
                verificationId,
                criterionKey,
                sampleIndex);
    }

    private Map<String, Object> sampleTimes(long invocationId) {
        return jdbc.queryForMap("SELECT started_at FROM capability_invocation WHERE id = ?", invocationId);
    }

    // ---------------------------------------------------------------- helpers

    /** lag ≤ 20（1 样本）→ consumer-running（2 样本，间隔 intervalSeconds），可再追加检查。 */
    private RecoveryPolicyCriteriaV1 policy(int intervalSeconds, RecoveryCriterionV1... extra) {
        List<RecoveryCriterionV1> criteria = new ArrayList<>();
        criteria.add(new RecoveryCriterionV1.QueueInspect(
                "stream-lag-drained",
                "积压达标",
                "statistics-stream",
                new QueueInspectArgumentsV1(),
                new RecoverySamplingV1(1, 0, null),
                new RecoveryPredicateV1.NumericCompare("lag", RecoveryPredicateV1.ComparisonOperator.LTE, 20.0),
                true));
        criteria.addAll(List.of(extra));
        criteria.add(new RecoveryCriterionV1.ServiceInspect(
                "consumer-running",
                "消费者持续运行",
                "statistics-consumer",
                new ServiceInspectArgumentsV1(),
                new RecoverySamplingV1(2, intervalSeconds, 2 * intervalSeconds + 2),
                new RecoveryPredicateV1.FieldEquals("runtimeState", "RUNNING"),
                true));
        return RecoveryPolicyCriteriaV1.of(60, 60, criteria);
    }

    /** 激活策略、按此刻配置生成快照，并以 TASK-080 的形态插入 PENDING Verification；Incident 为 VERIFYING。 */
    private long verification(RecoveryPolicyCriteriaV1 criteria) {
        long policyId = seeded.activateRecoveryPolicy(recoveryPolicies, "consumer-recovery", criteria);
        RecoveryPolicySnapshotV1 snapshot = RecoveryPolicySnapshotV1.of(
                selector.select(resources.findById(seeded.consumer()).orElseThrow()));
        long incidentId = seeded.investigation().incidentId();
        jdbc.update("UPDATE incident SET status = 'VERIFYING' WHERE id = ?", incidentId);
        jdbc.update(
                "INSERT INTO recovery_verification (incident_id, managed_resource_id, recovery_policy_id,"
                        + " recovery_policy_version, policy_snapshot, verification_no, status, deadline_at, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 1, ?, 1, 'PENDING', UTC_TIMESTAMP(3) + INTERVAL ? SECOND,"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                incidentId,
                seeded.consumer(),
                policyId,
                codecs.encode(RecoveryPolicySnapshotV1.SCHEMA_NAME, 1, snapshot),
                snapshot.maxDurationSeconds());
        jdbc.update("DELETE FROM incident_timeline_event");
        return jdbc.queryForObject(
                "SELECT id FROM recovery_verification WHERE incident_id = ?", Long.class, incidentId);
    }

    /** 直接登记一个已结束的样本槽位（模拟之前 Worker 的采样）。 */
    private long sampleRow(long verificationId, String criterionKey, int sampleIndex, String status) {
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, recovery_verification_id, criterion_key, sample_index,"
                        + " capability_key, managed_resource_id, status, request_schema_name, request_schema_version,"
                        + " request_payload, started_at, finished_at, duration_ms, error_code, error_message, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, 'service.inspect', ?, ?, 'service.inspect.request', 1, '{}',"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 5, 'TIMEOUT', 'timeout', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                seeded.investigation().incidentId(),
                verificationId,
                criterionKey,
                sampleIndex,
                seeded.consumer(),
                status);
        return jdbc.queryForObject(
                "SELECT id FROM capability_invocation WHERE recovery_verification_id = ? AND criterion_key = ?"
                        + " AND sample_index = ?",
                Long.class,
                verificationId,
                criterionKey,
                sampleIndex);
    }

    private void answer(String capability, Object... outcomes) {
        answers.put(capability, new ArrayDeque<>(List.of(outcomes)));
    }

    private static QueueInspectResultV1 queue(Long lag, long pending) {
        return new QueueInspectResultV1(
                QueueInspectResultV1.QueueType.REDIS_STREAM,
                1000,
                null,
                null,
                List.of(new QueueInspectResultV1.ConsumerGroup(GROUP, 1, pending, lag, null, null)));
    }

    private static ServiceInspectResultV1 service(RuntimeState state) {
        return new ServiceInspectResultV1(state, HealthStatus.NOT_CONFIGURED, null, 0, null, null, null);
    }

    private Map<String, Object> verificationRow(long verificationId) {
        return jdbc.queryForMap(
                "SELECT status, result_summary, deadline_at, IF(started_at IS NULL, 'NO', 'YES') AS has_started,"
                        + " CAST(result_payload AS CHAR) AS payload FROM recovery_verification WHERE id = ?",
                verificationId);
    }

    private RecoveryVerificationResultV1 result(long verificationId) {
        return codecs.decode(
                RecoveryVerificationResultV1.SCHEMA_NAME,
                1,
                (String) verificationRow(verificationId).get("payload"),
                RecoveryVerificationResultV1.class);
    }

    /** criterionKey/结果/原因，按快照顺序。 */
    private List<String> checkResults(long verificationId) {
        return result(verificationId).checks().stream()
                .map(check -> check.criterionKey() + "/" + check.result() + "/" + check.reason())
                .toList();
    }

    private List<Map<String, Object>> samples(long verificationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ci.id, ci.criterion_key, ci.sample_index, ci.status, ci.investigation_id, ci.run_no,"
                        + " ci.started_at, ci.finished_at, (SELECT COUNT(*) FROM observation o"
                        + " WHERE o.capability_invocation_id = ci.id AND o.recovery_verification_id = ?) AS observations"
                        + " FROM capability_invocation ci WHERE ci.recovery_verification_id = ?"
                        + " ORDER BY ci.criterion_key, ci.sample_index",
                verificationId,
                verificationId);
        rows.forEach(row -> row.replaceAll((key, value) ->
                value instanceof Number number && !(value instanceof Long) ? number.longValue() : value));
        return rows;
    }

    private Map<String, Object> investigationRow() {
        return jdbc.queryForMap(
                "SELECT * FROM investigation WHERE id = ?",
                seeded.investigation().investigationId());
    }

    /** 状态/版本/resolved_at 是否存在。 */
    private String incident() {
        return jdbc.queryForObject(
                "SELECT CONCAT(status, '/', lock_version, '/', IF(resolved_at IS NULL, '-', 'resolved'))"
                        + " FROM incident WHERE id = ?",
                String.class,
                seeded.investigation().incidentId());
    }

    /** 最后一个时间线事件的类型/发起方。 */
    private String lastEvent() {
        return jdbc.queryForObject(
                "SELECT CONCAT(event_type, '/', actor_type) FROM incident_timeline_event ORDER BY id DESC LIMIT 1",
                String.class);
    }

    /** RECOVERY_VERIFICATION_* 事件类型/整体结果（无则 -）。 */
    private List<String> verificationEvents() {
        return jdbc.queryForList(
                "SELECT CONCAT(event_type, '/', COALESCE(NULLIF(payload->>'$.overallResult', 'null'), '-'))"
                        + " FROM incident_timeline_event WHERE event_type LIKE 'RECOVERY_VERIFICATION_%' ORDER BY id",
                String.class);
    }

    private static Instant instant(Object value) {
        return value instanceof LocalDateTime local ? local.toInstant(ZoneOffset.UTC) : ((Timestamp) value).toInstant();
    }
}
