package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyActivationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySelector;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicySnapshotV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyValidator;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationApplicationService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationCreator;
import io.github.ismoyuan.opspilot.application.recovery.VerifyRecoveryCommand;
import io.github.ismoyuan.opspilot.application.recovery.VerifyRecoveryResult;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 08 TASK-081：真实 MySQL 上的外部处理后恢复验证入口。Incident 为 DIAGNOSED（版本 7）的已诊断夹具，统计消费者上的策略经正式激活服务
 * 启用；派发器为替身，以独立连接确认派发发生在提交之后。
 */
@SpringBootTest
@Testcontainers
@Import({
    RecoveryVerificationApplicationService.class,
    RecoveryVerificationCreator.class,
    RecoveryPolicySelector.class,
    RecoveryPolicyValidator.class,
    RecoveryPolicyActivationService.class,
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    ClockConfiguration.class
})
class VerifyRecoveryIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    RecoveryVerificationApplicationService service;

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

    @MockitoBean
    WorkDispatcher dispatcher;

    RemediationFixture seeded;

    /** 派发时以独立连接读到的 Verification 状态（未提交则 NOT_COMMITTED）。 */
    final List<String> seenAtDispatch = new ArrayList<>();

    @BeforeEach
    void seed() {
        seeded = RemediationFixture.seed(jdbc);
        seenAtDispatch.clear();
        doAnswer(invocation -> {
                    try (Connection other = DriverManager.getConnection(
                                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                            var query =
                                    other.prepareStatement("SELECT status FROM recovery_verification WHERE id = ?")) {
                        query.setLong(1, invocation.getArgument(0));
                        try (var rs = query.executeQuery()) {
                            seenAtDispatch.add(rs.next() ? rs.getString(1) : "NOT_COMMITTED");
                        }
                    }
                    return null;
                })
                .when(dispatcher)
                .dispatchRecoveryVerification(anyLong());
        jdbc.update("DELETE FROM incident_timeline_event");
    }

    /**
     * 05 §34：DIAGNOSED → VERIFYING；本事务选定的 ACTIVE 策略冻结为完整快照（与此刻选择器生成的一致）；action_execution_id 为空；
     * verification_no=1、deadline＝创建＋60 秒（策略 maxDurationSeconds）；写 RECOVERY_VERIFICATION_REQUESTED（USER、说明）；提交后派发。
     */
    @Test
    void anExternalFixCreatesAPendingVerificationFromTheActivePolicy() {
        long policyId = seeded.activateRecoveryPolicy(recoveryPolicies, "consumer-recovery");

        VerifyRecoveryResult result = service.verifyRecovery(command(7, "statistics-consumer", "  已在服务器手工恢复。 "));

        assertThat(result.incidentStatus()).isEqualTo(IncidentStatus.VERIFYING);
        assertThat(result.incidentVersion()).isEqualTo(8);
        assertThat(result.verificationNo()).isEqualTo(1);
        assertThat(result.verificationStatus()).isEqualTo(RecoveryVerificationStatus.PENDING);
        Map<String, Object> row = verificationRow(1);
        assertThat(row)
                .containsEntry("status", "PENDING")
                .containsEntry("action_execution_id", null)
                .containsEntry("managed_resource_id", seeded.consumer())
                .containsEntry("recovery_policy_id", policyId)
                .containsEntry("recovery_policy_version", 1L)
                .containsEntry("deadline_seconds", 60L);
        RecoveryPolicySnapshotV1 frozen = codecs.decode(
                RecoveryPolicySnapshotV1.SCHEMA_NAME, 1, (String) row.get("snapshot"), RecoveryPolicySnapshotV1.class);
        assertThat(frozen)
                .isEqualTo(RecoveryPolicySnapshotV1.of(
                        selector.select(resources.findById(seeded.consumer()).orElseThrow())));
        assertThat(incident()).isEqualTo("VERIFYING/8");
        assertThat(jdbc.queryForMap("SELECT event_type, actor_type, actor_id, payload->>'$.note' AS note,"
                        + " payload->>'$.verificationNo' AS no FROM incident_timeline_event"))
                .containsEntry("event_type", "RECOVERY_VERIFICATION_REQUESTED")
                .containsEntry("actor_type", "USER")
                .containsEntry("actor_id", "demo-user")
                .containsEntry("note", "已在服务器手工恢复。")
                .containsEntry("no", "1");
        verify(dispatcher).dispatchRecoveryVerification((long) row.get("id"));
        assertThat(seenAtDispatch).containsExactly("PENDING");
    }

    /** 每个拒绝分支都在任何写入之前：没有 Verification、Incident 与版本不变、没有时间线、不派发。 */
    @Test
    void rejectionsWriteNothing() {
        record Case(String name, Runnable setup, VerifyRecoveryCommand command, ErrorCode code) {}
        List<Case> cases = List.of(
                new Case(
                        "no active policy",
                        () -> {},
                        command(7, "statistics-consumer", null),
                        ErrorCode.RECOVERY_POLICY_NOT_FOUND),
                new Case(
                        "wrong state",
                        () -> jdbc.update("UPDATE incident SET status = 'INVESTIGATING' WHERE id = ?", incidentId()),
                        command(7, "statistics-consumer", null),
                        ErrorCode.INCIDENT_STATE_CONFLICT),
                new Case(
                        "stale version",
                        () -> {},
                        command(6, "statistics-consumer", null),
                        ErrorCode.INCIDENT_VERSION_CONFLICT),
                new Case(
                        "unknown resource",
                        () -> {},
                        command(7, "no-such-resource", null),
                        ErrorCode.RESOURCE_NOT_IN_SYSTEM),
                new Case(
                        "note too long",
                        () -> {},
                        command(7, "statistics-consumer", "长".repeat(501)),
                        ErrorCode.REQUEST_VALIDATION_FAILED),
                new Case(
                        "verification running",
                        () -> insertVerification(1, "RUNNING"),
                        command(7, "statistics-consumer", null),
                        ErrorCode.RECOVERY_VERIFICATION_ALREADY_RUNNING),
                new Case(
                        "ambiguous policy",
                        () -> jdbc.update(
                                "INSERT INTO recovery_policy (managed_resource_id, policy_key, name, version_no,"
                                        + " criteria_schema_name, criteria_schema_version, criteria_payload, status,"
                                        + " created_at, activated_at) SELECT managed_resource_id, 'second', name, 1,"
                                        + " criteria_schema_name, criteria_schema_version, criteria_payload, 'ACTIVE',"
                                        + " created_at, activated_at FROM recovery_policy WHERE status = 'ACTIVE'"),
                        command(7, "statistics-consumer", null),
                        ErrorCode.RECOVERY_POLICY_AMBIGUOUS));
        for (Case scenario : cases) {
            seed();
            if (!scenario.name().equals("no active policy")) {
                seeded.activateRecoveryPolicy(recoveryPolicies, "consumer-recovery");
            }
            scenario.setup().run();
            String incidentBefore = incident();
            long verificationsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM recovery_verification", Long.class);

            assertThat(outcome(() -> service.verifyRecovery(scenario.command())))
                    .as(scenario.name())
                    .isEqualTo(scenario.code());

            assertThat(incident()).as(scenario.name()).isEqualTo(incidentBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recovery_verification", Long.class))
                    .as(scenario.name())
                    .isEqualTo(verificationsBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_timeline_event", Long.class))
                    .as(scenario.name())
                    .isZero();
        }
        verify(dispatcher, never()).dispatchRecoveryVerification(anyLong());
    }

    /** 05 §35：上一轮 INCONCLUSIVE 回 DIAGNOSED 后再次调用，新建 verificationNo=2；原记录的结果与期限不被更新。 */
    @Test
    void aNewVerificationAfterAnInconclusiveOneGetsTheNextNumberAndLeavesTheOldOneIntact() {
        seeded.activateRecoveryPolicy(recoveryPolicies, "consumer-recovery");
        insertVerification(1, "INCONCLUSIVE");
        Map<String, Object> old = verificationRow(1);

        VerifyRecoveryResult result = service.verifyRecovery(command(7, "statistics-consumer", null));

        assertThat(result.verificationNo()).isEqualTo(2);
        assertThat(verificationRow(1)).isEqualTo(old);
        assertThat(verificationRow(2)).containsEntry("status", "PENDING");
    }

    /** 同一版本的并发请求：Incident 行锁串行化，只有一个成功（另一个版本或状态冲突），只有一个 Verification。 */
    @Test
    void concurrentRequestsCreateOneVerification() throws Exception {
        seeded.activateRecoveryPolicy(recoveryPolicies, "consumer-recovery");
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
                return outcome(() -> service.verifyRecovery(command(7, "statistics-consumer", null)));
            }));
        }
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (var call : calls) {
            outcomes.add(call.get(30, TimeUnit.SECONDS));
        }

        assertThat(outcomes).filteredOn(VerifyRecoveryResult.class::isInstance).hasSize(1);
        assertThat(outcomes)
                .filteredOn(ErrorCode.class::isInstance)
                .hasSize(3)
                .allSatisfy(code ->
                        assertThat(code).isIn(ErrorCode.INCIDENT_VERSION_CONFLICT, ErrorCode.INCIDENT_STATE_CONFLICT));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recovery_verification", Long.class))
                .isEqualTo(1L);
    }

    // ---------------------------------------------------------------- helpers

    private VerifyRecoveryCommand command(long expectedVersion, String resourceKey, String note) {
        return new VerifyRecoveryCommand(seeded.incidentKey(), expectedVersion, resourceKey, note, "demo-user");
    }

    private long incidentId() {
        return seeded.investigation().incidentId();
    }

    /** 以策略 v1 的快照插入一个指定状态的 Verification（模拟之前的验证）。 */
    private void insertVerification(int number, String status) {
        boolean terminal = !status.equals("PENDING") && !status.equals("RUNNING");
        jdbc.update(
                "INSERT INTO recovery_verification (incident_id, managed_resource_id, recovery_policy_id,"
                        + " recovery_policy_version, policy_snapshot, verification_no, status, result_summary,"
                        + " result_payload, deadline_at, started_at, finished_at, created_at, updated_at) SELECT ?,"
                        + " managed_resource_id, id, version_no, JSON_OBJECT('schemaName', 'recovery.policy.snapshot',"
                        + " 'schemaVersion', 1), ?, ?, ?, ?, UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE,"
                        + " UTC_TIMESTAMP(3) - INTERVAL 3 MINUTE, ?, UTC_TIMESTAMP(3) - INTERVAL 3 MINUTE,"
                        + " UTC_TIMESTAMP(3) - INTERVAL 3 MINUTE FROM recovery_policy WHERE status = 'ACTIVE'",
                incidentId(),
                number,
                status,
                terminal ? "恢复验证无法确认" : null,
                terminal ? "{\"schemaName\": \"recovery.verification.result\", \"schemaVersion\": 1}" : null,
                terminal ? java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(2) : null);
    }

    private Map<String, Object> verificationRow(int number) {
        Map<String, Object> row = new java.util.HashMap<>(jdbc.queryForMap(
                "SELECT id, status, action_execution_id, managed_resource_id, recovery_policy_id, recovery_policy_version,"
                        + " CAST(policy_snapshot AS CHAR) AS snapshot, result_summary, deadline_at,"
                        + " TIMESTAMPDIFF(SECOND, created_at, deadline_at) AS deadline_seconds"
                        + " FROM recovery_verification WHERE incident_id = ? AND verification_no = ?",
                incidentId(),
                number));
        row.replaceAll((key, value) -> value instanceof Number n ? n.longValue() : value);
        return row;
    }

    private String incident() {
        return jdbc.queryForObject(
                "SELECT CONCAT(status, '/', lock_version) FROM incident WHERE id = ?", String.class, incidentId());
    }

    /** 成功返回结果，业务拒绝返回错误码。 */
    private static Object outcome(java.util.function.Supplier<Object> call) {
        try {
            return call.get();
        } catch (OpsPilotException ex) {
            return ex.errorCode();
        }
    }
}
