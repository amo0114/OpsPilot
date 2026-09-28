package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CacheInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.QueueInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RequestCapability;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAdmission;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAdmissionService;
import io.github.ismoyuan.opspilot.application.capability.CapabilityExecutionResult;
import io.github.ismoyuan.opspilot.application.capability.CapabilityExecutionService;
import io.github.ismoyuan.opspilot.application.capability.CapabilityInvoker;
import io.github.ismoyuan.opspilot.application.capability.CapabilityProviderResolver;
import io.github.ismoyuan.opspilot.application.capability.CapabilityResultRecorder;
import io.github.ismoyuan.opspilot.application.capability.DuplicateGuard;
import io.github.ismoyuan.opspilot.application.capability.InvocationOutcome;
import io.github.ismoyuan.opspilot.application.capability.ObservationDraft;
import io.github.ismoyuan.opspilot.application.investigation.recovery.InvestigationInterruptionRecorder;
import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上的调查 OBSERVE 调用：唯一规范 JSON 与 Duplicate Guard（08 TASK-047）、原子准入与结果落账骨架（08 TASK-048）。
 * CapabilityInvoker 为测试替身（真实 Provider 链路属 TASK-049～057）；它被调用时断言不在任何数据库事务内。
 */
@SpringBootTest
@Testcontainers
@Import({
    CapabilityAdmissionService.class,
    CapabilityAccess.class,
    CapabilityProviderResolver.class,
    DuplicateGuard.class,
    CapabilityResultRecorder.class,
    CapabilityExecutionService.class,
    InvestigationInterruptionRecorder.class,
    ClockConfiguration.class,
    CapabilityExecutionIntegrationTest.ScriptedInvoker.class
})
class CapabilityExecutionIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    static final AtomicReference<Function<AdmittedInvocation, InvocationOutcome>> BEHAVIOR = new AtomicReference<>();
    static final List<Boolean> TRANSACTION_ACTIVE_DURING_INVOKE = new ArrayList<>();

    @TestConfiguration
    static class ScriptedInvoker {
        @Bean
        CapabilityInvoker capabilityInvoker() {
            return invocation -> {
                TRANSACTION_ACTIVE_DURING_INVOKE.add(TransactionSynchronizationManager.isActualTransactionActive());
                return BEHAVIOR.get().apply(invocation);
            };
        }
    }

    @Autowired
    CapabilityAdmissionService admissions;

    @Autowired
    CapabilityResultRecorder results;

    @Autowired
    CapabilityExecutionService execution;

    @Autowired
    InvestigationInterruptionRecorder interruptions;

    @Autowired
    JdbcTemplate jdbc;

    InvestigationFixture fixture;
    long incident;
    long service;
    long stream;

    @BeforeEach
    void seed() {
        for (String table : List.of("capability_binding", "resource_binding", "data_source_connection")) {
            jdbc.update("DELETE FROM " + table);
        }
        fixture = InvestigationFixture.reset(jdbc);
        incident = fixture.incidentId();
        long system = jdbc.queryForObject("SELECT id FROM managed_system", Long.class);
        jdbc.update("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) VALUES (" + system + ", 'redirect-service', 'Redirect', 'SERVICE',"
                + " 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        service = resourceId("redirect-service");
        stream = resourceId("statistics-stream");
        long prometheus = connection("prometheus-local", "PROMETHEUS");
        long redis = connection("redis-local", "REDIS");
        bind(
                service,
                prometheus,
                "prometheus.resource.binding",
                "{\"labels\": {\"application\": \"shortlink-project\"}, \"metrics\": {"
                        + "\"http.request.latency.p99\": {\"queryTemplate\": \"up\", \"unit\": \"ms\"},"
                        + "\"http.request.rate\": {\"queryTemplate\": \"up\", \"unit\": \"req/s\"}}}");
        bind(
                stream,
                redis,
                "redis.resource.binding",
                "{\"streamKey\": \"shortlink:stats\", \"consumerGroup\": \"stats-consumer-group\"}");
        capability(service, "metrics.query");
        capability(stream, "queue.inspect");
        TRANSACTION_ACTIVE_DURING_INVOKE.clear();
        BEHAVIOR.set(invocation -> succeeded());
    }

    // ---------------------------------------------------------------- TASK-048 准入

    /** 准入成功：RUNNING Invocation（run_no、请求 Schema、规范 JSON 载荷），本轮与累计计数各 +1，均在同一次提交中。 */
    @Test
    void admissionRegistersARunningCallAndCountsIt() {
        CapabilityAdmission admission =
                admissions.admit(incident, 1, metrics("http.request.latency.p99", WindowKey.LAST_15_MIN, true));

        assertThat(admission).isInstanceOf(CapabilityAdmission.Admitted.class);
        long id = ((CapabilityAdmission.Admitted) admission).invocation().invocationId();
        assertThat(jdbc.queryForMap(
                        "SELECT status, CAST(run_no AS SIGNED) AS run_no, capability_key, request_schema_name,"
                                + " CAST(request_schema_version AS SIGNED) AS request_schema_version,"
                                + " CAST(managed_resource_id AS SIGNED) AS resource FROM capability_invocation WHERE id = ?",
                        id))
                .containsEntry("status", "RUNNING")
                .containsEntry("run_no", 1L)
                .containsEntry("capability_key", "metrics.query")
                .containsEntry("request_schema_name", "metrics.query.request")
                .containsEntry("request_schema_version", 1L)
                .containsEntry("resource", service);
        assertThat(jdbc.queryForObject(
                        "SELECT JSON_EXTRACT(request_payload, '$.windowKey') FROM capability_invocation WHERE id = ?",
                        String.class,
                        id))
                .isEqualTo("\"LAST_15_MIN\"");
        assertThat(counts()).containsExactly(1L, 1L);
    }

    /** 调查状态不允许（Stop、本轮额度用尽、旧 run）：不建调用、不扣预算。 */
    @Test
    void investigationStateRejectionsWriteNothing() {
        update("current_run_capability_count = 12, capability_call_count = 30");
        assertNotAdmitted(StepAdmissionRejection.CAPABILITY_BUDGET_EXHAUSTED, 1);
        update("current_run_capability_count = 3");
        assertNotAdmitted(StepAdmissionRejection.STALE_RUN, 2);
        update("stop_requested_at = UTC_TIMESTAMP(3), stop_requested_by = 'demo-user'");
        assertNotAdmitted(StepAdmissionRejection.STOP_REQUESTED, 1);

        assertThat(count("capability_invocation")).isZero();
        assertThat(counts()).containsExactly(3L, 30L);
    }

    /** 能力规则拒绝（06 §16、§22、§42）：各自的错误码与原因，都不建调用、不扣预算。 */
    @Test
    void capabilityRuleRejectionsWriteNothing() {
        long otherSystemResource = otherSystemResource();
        assertRejected(
                new RequestCapability.QueueInspect(otherSystemResource, new QueueInspectArgumentsV1(), "核对积压"),
                ErrorCode.CAPABILITY_NOT_ALLOWED,
                "RESOURCE_NOT_IN_SYSTEM");
        assertRejected(
                new RequestCapability.CacheInspect(service, new CacheInspectArgumentsV1(), "检查缓存"),
                ErrorCode.CAPABILITY_NOT_ALLOWED,
                "RESOURCE_TYPE_NOT_SUPPORTED");
        jdbc.update("UPDATE capability_binding SET enabled = FALSE WHERE managed_resource_id = ?", stream);
        assertRejected(
                new RequestCapability.QueueInspect(stream, new QueueInspectArgumentsV1(), "核对积压"),
                ErrorCode.CAPABILITY_NOT_BOUND,
                "BINDING_MISSING_OR_DISABLED");
        assertRejected(
                metrics("jvm.cpu.usage", WindowKey.LAST_15_MIN, false),
                ErrorCode.CAPABILITY_ARGUMENT_INVALID,
                "METRIC_KEY_NOT_AVAILABLE");
        assertRejected(
                metrics("http.request.rate", WindowKey.LAST_60_MIN, true),
                ErrorCode.METRIC_COMPARISON_WINDOW_EXCEEDS_LIMIT,
                "TOTAL_RANGE_OVER_60_MIN");
        jdbc.update("UPDATE data_source_connection SET status = 'DISABLED' WHERE connection_key = 'prometheus-local'");
        assertRejected(
                metrics("http.request.rate", WindowKey.LAST_15_MIN, false),
                ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED,
                "NO_ACTIVE_PROVIDER");
        jdbc.update("UPDATE managed_resource SET status = 'DISABLED' WHERE id = ?", service);
        assertRejected(
                metrics("http.request.rate", WindowKey.LAST_15_MIN, false),
                ErrorCode.CAPABILITY_NOT_ALLOWED,
                "RESOURCE_NOT_ACTIVE");

        assertThat(count("capability_invocation")).isZero();
        assertThat(counts()).containsExactly(0L, 0L);
    }

    /** LAST_30_MIN＋比较（总范围 60 分钟）在上限内，可以准入。 */
    @Test
    void comparisonWithinSixtyMinutesIsAdmitted() {
        assertThat(admissions.admit(incident, 1, metrics("http.request.rate", WindowKey.LAST_30_MIN, true)))
                .isInstanceOf(CapabilityAdmission.Admitted.class);
    }

    /**
     * INCIDENT_CONTEXT 在准入时按 Incident 开始时间解析后再判断比较范围（B14-R1）：故障已 45 分钟时比较总范围 90 分钟，准入前拒绝，
     * 不建调用、不扣预算；20 分钟时准入，解析出的等长紧邻窗口随准入结果交给 Provider。
     */
    @Test
    void incidentContextIsResolvedAtAdmissionBeforeAnyBudgetIsSpent() {
        jdbc.update("UPDATE incident SET started_at = UTC_TIMESTAMP(3) - INTERVAL 45 MINUTE WHERE id = ?", incident);
        assertRejected(
                metrics("http.request.rate", WindowKey.INCIDENT_CONTEXT, true),
                ErrorCode.METRIC_COMPARISON_WINDOW_EXCEEDS_LIMIT,
                "TOTAL_RANGE_OVER_60_MIN");
        assertThat(count("capability_invocation")).isZero();
        assertThat(counts()).containsExactly(0L, 0L);

        jdbc.update("UPDATE incident SET started_at = UTC_TIMESTAMP(3) - INTERVAL 20 MINUTE WHERE id = ?", incident);
        CapabilityAdmission admission =
                admissions.admit(incident, 1, metrics("http.request.rate", WindowKey.INCIDENT_CONTEXT, true));

        AdmittedInvocation admitted = ((CapabilityAdmission.Admitted) admission).invocation();
        assertThat(admitted.window().current().end()).isEqualTo(admitted.startedAt());
        assertThat(admitted.window().current().length()).isBetween(Duration.ofMinutes(19), Duration.ofMinutes(21));
        assertThat(admitted.window().previous().end())
                .isEqualTo(admitted.window().current().start());
        assertThat(admitted.window().previous().length())
                .isEqualTo(admitted.window().current().length());
        assertThat(counts()).containsExactly(1L, 1L);
    }

    // ---------------------------------------------------------------- TASK-047 Duplicate Guard

    /**
     * 同指纹的在途调用、finished_at 在窗口内的终态调用（即使 created_at 很早）、上一 run 的同指纹调用、键序不同的已存载荷均被拒绝，
     * 且不建调用、不扣预算；窗口外结束的与参数不同的可以准入。
     */
    @Test
    void duplicateGuardFollowsTheFingerprintAndFinishedAtWindow() {
        String payload = "{\"windowKey\": \"LAST_15_MIN\", \"metricKey\": \"http.request.rate\","
                + " \"comparePreviousWindow\": false}";
        long inFlight = existingCall("RUNNING", 1, payload, 5, null);
        assertRejected(
                metrics("http.request.rate", WindowKey.LAST_15_MIN, false),
                ErrorCode.CAPABILITY_DUPLICATE_REQUEST,
                "RECENT_OR_IN_FLIGHT");

        jdbc.update("DELETE FROM capability_invocation WHERE id = ?", inFlight);
        existingCall("SUCCEEDED", 1, payload, 600, 10);
        assertRejected(
                metrics("http.request.rate", WindowKey.LAST_15_MIN, false),
                ErrorCode.CAPABILITY_DUPLICATE_REQUEST,
                "RECENT_OR_IN_FLIGHT");

        // 上一 run 的同指纹调用刚结束：run_no 不在指纹中
        update("current_run_no = 2");
        assertThat(admissions.admit(incident, 2, metrics("http.request.rate", WindowKey.LAST_15_MIN, false)))
                .isEqualTo(new CapabilityAdmission.Rejected(
                        ErrorCode.CAPABILITY_DUPLICATE_REQUEST, "RECENT_OR_IN_FLIGHT"));
        assertThat(count("capability_invocation")).isOne();
        assertThat(counts()).containsExactly(0L, 0L);

        // 参数不同即不同指纹
        assertThat(admissions.admit(incident, 2, metrics("http.request.rate", WindowKey.LAST_30_MIN, false)))
                .isInstanceOf(CapabilityAdmission.Admitted.class);
    }

    /** 窗口外结束的同指纹调用不阻止新请求；历史条数可远超 12，查询只按状态与 finished_at 筛选。 */
    @Test
    void callsFinishedOutsideTheWindowDoNotBlockEvenWithLongHistory() {
        String payload =
                "{\"comparePreviousWindow\":false,\"metricKey\":\"http.request.rate\",\"windowKey\":\"LAST_15_MIN\"}";
        for (int i = 0; i < 20; i++) {
            existingCall(i % 2 == 0 ? "SUCCEEDED" : "FAILED", 1, payload, 3_600, 31 + i);
        }

        assertThat(admissions.admit(incident, 1, metrics("http.request.rate", WindowKey.LAST_15_MIN, false)))
                .isInstanceOf(CapabilityAdmission.Admitted.class);
        assertThat(counts()).containsExactly(1L, 1L);
    }

    // ---------------------------------------------------------------- TASK-048 结果事务与执行骨架

    /** 执行成功：Invoker 在事务外调用；Invocation 记为 SUCCEEDED 并以其上下文产生 Observation；预算不再变化。 */
    @Test
    void successfulExecutionRecordsTheResultAndObservations() {
        CapabilityExecutionResult result =
                execution.execute(incident, 1, metrics("http.request.latency.p99", WindowKey.LAST_15_MIN, false));

        assertThat(result).isInstanceOf(CapabilityExecutionResult.Succeeded.class);
        long id = ((CapabilityExecutionResult.Succeeded) result).invocationId();
        assertThat(TRANSACTION_ACTIVE_DURING_INVOKE).containsExactly(false);
        assertThat(jdbc.queryForMap(
                        "SELECT status, response_schema_name, raw_result_ref, error_code, finished_at IS NOT NULL AS finished"
                                + " FROM capability_invocation WHERE id = ?",
                        id))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("response_schema_name", "metrics.query.result")
                .containsEntry("raw_result_ref", "file:///var/opspilot/raw/1.json")
                .containsEntry("error_code", null)
                .containsEntry("finished", 1L);
        assertThat(jdbc.queryForMap(
                        "SELECT CAST(incident_id AS SIGNED) AS incident, CAST(investigation_id AS SIGNED) AS investigation,"
                                + " CAST(managed_resource_id AS SIGNED) AS resource, observation_kind"
                                + " FROM observation WHERE capability_invocation_id = ?",
                        id))
                .containsEntry("incident", incident)
                .containsEntry("investigation", fixture.investigationId())
                .containsEntry("resource", service)
                .containsEntry("observation_kind", "METRIC");
        assertThat(counts()).containsExactly(1L, 1L);
    }

    /** 调用失败只记错误、不产生 Observation；Invoker 抛出的意外异常按 CAPABILITY_INVOCATION_FAILED 记录，预算不退还。 */
    @Test
    void failuresRecordOnlyTheErrorAndKeepTheBudget() {
        BEHAVIOR.set(invocation ->
                new InvocationOutcome.Failed(ErrorCode.CAPABILITY_INVOCATION_FAILED, "Prometheus unavailable"));
        CapabilityExecutionResult failed =
                execution.execute(incident, 1, metrics("http.request.latency.p99", WindowKey.LAST_15_MIN, false));
        BEHAVIOR.set(invocation -> {
            throw new IllegalStateException("provider bug");
        });
        CapabilityExecutionResult crashed = execution.execute(
                incident, 1, new RequestCapability.QueueInspect(stream, new QueueInspectArgumentsV1(), "核对积压"));

        assertThat(failed).isInstanceOf(CapabilityExecutionResult.Failed.class);
        assertThat(crashed)
                .isEqualTo(new CapabilityExecutionResult.Failed(
                        ((CapabilityExecutionResult.Failed) crashed).invocationId(),
                        ErrorCode.CAPABILITY_INVOCATION_FAILED));
        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(status, '/', error_code, '/', error_message) FROM capability_invocation ORDER BY id",
                        String.class))
                .containsExactly(
                        "FAILED/CAPABILITY_INVOCATION_FAILED/Prometheus unavailable",
                        "FAILED/CAPABILITY_INVOCATION_FAILED/Capability invocation failed unexpectedly");
        assertThat(count("observation WHERE capability_invocation_id IN (SELECT id FROM capability_invocation)"))
                .isZero();
        assertThat(counts()).containsExactly(2L, 2L);
    }

    /** 终态只从 RUNNING 条件更新：已终结的调用再次落账不覆盖；中断标记后的迟到结果不写入，也不退还预算。 */
    @Test
    void terminalStatesAreNeverOverwritten() {
        long first = admitted(metrics("http.request.latency.p99", WindowKey.LAST_15_MIN, false));
        assertThat(results.recordFailed(first, ErrorCode.CAPABILITY_INVOCATION_FAILED, "timeout"))
                .isTrue();
        assertThat(results.recordSucceeded(first, succeeded())).isEmpty();
        assertThat(results.recordFailed(first, ErrorCode.CAPABILITY_INVOCATION_FAILED, "again"))
                .isFalse();
        assertThat(status(first)).isEqualTo("FAILED");

        long second = admitted(new RequestCapability.QueueInspect(stream, new QueueInspectArgumentsV1(), "核对积压"));
        interruptions.recordInterrupted(Instant.now().plusSeconds(60)); // 模拟重启：此前开始的 RUNNING 属旧进程
        assertThat(results.recordSucceeded(second, succeeded())).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT error_code FROM capability_invocation WHERE id = ?", String.class, second))
                .isEqualTo("PROCESS_INTERRUPTED");
        assertThat(count("observation WHERE capability_invocation_id = " + second))
                .isZero();
        assertThat(counts()).containsExactly(2L, 2L);
    }

    /** 旧 run 在途调用的真实结果写回原 Invocation，Observation 归属原调用；新 run 的计数与状态不变（01 §11）。 */
    @Test
    void anOldRunResultIsKeptOnTheOriginalCallWithoutTouchingTheNewRun() {
        long call = admitted(metrics("http.request.latency.p99", WindowKey.LAST_15_MIN, false));
        update("current_run_no = 2, current_run_capability_count = 0");
        Map<String, Object> before = runControl();

        assertThat(results.recordSucceeded(call, succeeded())).isPresent();

        assertThat(status(call)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                        "SELECT CAST(run_no AS SIGNED) FROM capability_invocation WHERE id = ?", Long.class, call))
                .isOne();
        assertThat(count("observation WHERE capability_invocation_id = " + call))
                .isOne();
        assertThat(runControl()).isEqualTo(before);
    }

    /**
     * 迟到结果未被采用时如实报告（B14-R1）：执行期间调用已被中断标记，随后返回的成功或失败都不写入，执行服务返回 Discarded，
     * 数据库保持 FAILED/PROCESS_INTERRUPTED、没有 Observation。
     */
    @Test
    void lateOutcomesOfAnAlreadyFinishedCallAreReportedAsDiscarded() {
        BEHAVIOR.set(invocation -> {
            interruptions.recordInterrupted(Instant.now().plusSeconds(60));
            return succeeded();
        });
        CapabilityExecutionResult lateSuccess =
                execution.execute(incident, 1, metrics("http.request.latency.p99", WindowKey.LAST_15_MIN, false));
        BEHAVIOR.set(invocation -> {
            interruptions.recordInterrupted(Instant.now().plusSeconds(60));
            return new InvocationOutcome.Failed(ErrorCode.CAPABILITY_INVOCATION_FAILED, "timeout");
        });
        CapabilityExecutionResult lateFailure = execution.execute(
                incident, 1, new RequestCapability.QueueInspect(stream, new QueueInspectArgumentsV1(), "核对积压"));

        assertThat(lateSuccess).isInstanceOf(CapabilityExecutionResult.Discarded.class);
        assertThat(lateFailure).isInstanceOf(CapabilityExecutionResult.Discarded.class);
        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(status, '/', error_code) FROM capability_invocation ORDER BY id", String.class))
                .containsExactly("FAILED/PROCESS_INTERRUPTED", "FAILED/PROCESS_INTERRUPTED");
        assertThat(count("observation WHERE capability_invocation_id IN (SELECT id FROM capability_invocation)"))
                .isZero();
    }

    /** 没有 CapabilityInvoker 时拒绝执行且不做准入：不建调用、不扣预算（真实 Invoker 属 TASK-049～057）。 */
    @Test
    void withoutAnInvokerNothingIsAdmitted() {
        CapabilityExecutionService unwired = new CapabilityExecutionService(
                admissions, results, new DefaultListableBeanFactory().getBeanProvider(CapabilityInvoker.class));

        assertThatThrownBy(
                        () -> unwired.execute(incident, 1, metrics("http.request.rate", WindowKey.LAST_15_MIN, false)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("capability_invocation")).isZero();
        assertThat(counts()).containsExactly(0L, 0L);
    }

    // ---------------------------------------------------------------- helpers

    private RequestCapability metrics(String metricKey, WindowKey window, boolean compare) {
        return new RequestCapability.MetricsQuery(
                service, new MetricsQueryArgumentsV1(metricKey, window, compare), "确认业务 HTTP 影响");
    }

    private static InvocationOutcome.Succeeded succeeded() {
        Instant now = Instant.now();
        return new InvocationOutcome.Succeeded(
                new CapabilitySchema("metrics.query.result", 1),
                "{\"metricKey\": \"http.request.latency.p99\", \"latest\": 1640}",
                "file:///var/opspilot/raw/1.json",
                List.of(new ObservationDraft(
                        ObservationKind.METRIC,
                        "metric.observation",
                        1,
                        "{\"metricKey\": \"http.request.latency.p99\", \"latest\": 1640}",
                        "P99 1640 ms",
                        now,
                        now.minusSeconds(900),
                        now)));
    }

    private long admitted(RequestCapability request) {
        return ((CapabilityAdmission.Admitted) admissions.admit(incident, 1, request))
                .invocation()
                .invocationId();
    }

    private void assertNotAdmitted(StepAdmissionRejection reason, int runNo) {
        assertThat(admissions.admit(incident, runNo, metrics("http.request.rate", WindowKey.LAST_15_MIN, false)))
                .isEqualTo(new CapabilityAdmission.NotAdmitted(reason));
    }

    private void assertRejected(RequestCapability request, ErrorCode code, String reason) {
        assertThat(admissions.admit(incident, 1, request)).isEqualTo(new CapabilityAdmission.Rejected(code, reason));
    }

    private long existingCall(
            String status, int runNo, String payload, int createdSecondsAgo, Integer finishedSecondsAgo) {
        boolean terminal = finishedSecondsAgo != null;
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key,"
                        + " managed_resource_id, status, request_schema_name, request_schema_version, request_payload,"
                        + " response_schema_name, response_schema_version, response_payload, started_at, finished_at,"
                        + " duration_ms, error_code, error_message, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'metrics.query', ?, ?, 'metrics.query.request', 1, ?, ?, ?, ?,"
                        + " UTC_TIMESTAMP(3) - INTERVAL ? SECOND, "
                        + (terminal ? "UTC_TIMESTAMP(3) - INTERVAL " + finishedSecondsAgo + " SECOND" : "NULL")
                        + ", ?, ?, ?, UTC_TIMESTAMP(3) - INTERVAL ? SECOND, UTC_TIMESTAMP(3))",
                incident,
                fixture.investigationId(),
                runNo,
                service,
                status,
                payload,
                status.equals("SUCCEEDED") ? "metrics.query.result" : null,
                status.equals("SUCCEEDED") ? 1 : null,
                status.equals("SUCCEEDED") ? "{}" : null,
                createdSecondsAgo,
                terminal ? 10 : null,
                status.equals("FAILED") ? "CAPABILITY_INVOCATION_FAILED" : null,
                status.equals("FAILED") ? "failed" : null,
                createdSecondsAgo);
        return jdbc.queryForObject("SELECT MAX(id) FROM capability_invocation", Long.class);
    }

    private long otherSystemResource() {
        jdbc.update("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('other-platform', 'O', 'DEMO', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) SELECT id, 'other-stream', 'O', 'MESSAGE_QUEUE', 'ACTIVE',"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM managed_system WHERE system_key = 'other-platform'");
        long other =
                jdbc.queryForObject("SELECT id FROM managed_resource WHERE resource_key = 'other-stream'", Long.class);
        bind(
                other,
                jdbc.queryForObject(
                        "SELECT id FROM data_source_connection WHERE connection_key = 'redis-local'", Long.class),
                "redis.resource.binding",
                "{\"streamKey\": \"other:stats\", \"consumerGroup\": \"other-group\"}");
        capability(other, "queue.inspect");
        return other;
    }

    private List<Long> counts() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT CAST(current_run_capability_count AS SIGNED) AS current_count,"
                        + " CAST(capability_call_count AS SIGNED) AS total FROM investigation WHERE id = ?",
                fixture.investigationId());
        return List.of((Long) row.get("current_count"), (Long) row.get("total"));
    }

    private Map<String, Object> runControl() {
        return jdbc.queryForMap(
                "SELECT current_run_no, current_run_capability_count, capability_call_count, lock_version"
                        + " FROM investigation WHERE id = ?",
                fixture.investigationId());
    }

    private void update(String assignments) {
        jdbc.update("UPDATE investigation SET " + assignments + " WHERE id = ?", fixture.investigationId());
    }

    private String status(long invocationId) {
        return jdbc.queryForObject("SELECT status FROM capability_invocation WHERE id = ?", String.class, invocationId);
    }

    private int count(String tableAndWhere) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + tableAndWhere, Integer.class);
    }

    private long resourceId(String key) {
        return jdbc.queryForObject("SELECT id FROM managed_resource WHERE resource_key = ?", Long.class, key);
    }

    private long connection(String key, String providerType) {
        jdbc.update(
                "INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, config_schema_name,"
                        + " config_schema_version, config_payload, status, created_at, updated_at) VALUES (?, ?, ?,"
                        + " 'tcp://local:1', 'test.connection.config', 1, '{}', 'ACTIVE', UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3))",
                key,
                key,
                providerType);
        return jdbc.queryForObject("SELECT id FROM data_source_connection WHERE connection_key = ?", Long.class, key);
    }

    private void bind(long resourceId, long connectionId, String schemaName, String payload) {
        jdbc.update(
                "INSERT INTO resource_binding (managed_resource_id, data_source_connection_id, selector_schema_name,"
                        + " selector_schema_version, selector_payload, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                resourceId,
                connectionId,
                schemaName,
                payload);
    }

    private void capability(long resourceId, String key) {
        jdbc.update(
                "INSERT INTO capability_binding (managed_resource_id, capability_key, enabled, created_at, updated_at)"
                        + " VALUES (?, ?, TRUE, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                resourceId,
                key);
    }
}
