package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.investigation.ContinueInvestigationCommand;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRunResult;
import io.github.ismoyuan.opspilot.application.investigation.StartInvestigationCommand;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
 * 真实 MySQL 上验证开始/继续调查（08 TASK-016～017、01 §9、05 §24/§28）：唯一 Investigation 与 run 字段、
 * 条件迁移与时间线同事务、提交后才派发、冲突与并发只准入一个。WorkDispatcher 为测试替身。
 */
@SpringBootTest(properties = "opspilot.investigation.max-capability-calls=7")
@Testcontainers
@Import({InvestigationApplicationService.class, InvestigationRunIntegrationTest.FixedClock.class})
class InvestigationRunIntegrationTest {

    static final Instant NOW = Instant.parse("2026-09-27T08:00:00.250Z");
    static final String KEY = "INC-20260927-0001";

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
    InvestigationApplicationService service;

    @MockitoBean
    WorkDispatcher dispatcher;

    @MockitoSpyBean
    TimelineRepository timeline;

    @Autowired
    JdbcTemplate jdbc;

    long incidentId;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM incident_timeline_event");
        jdbc.update("DELETE FROM investigation");
        jdbc.update("DELETE FROM incident");
        jdbc.update("DELETE FROM managed_system");
        jdbc.update("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at) VALUES"
                + " ('shortlink-platform', 'ShortLink', 'DEMO', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        jdbc.update(
                "INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status,"
                        + " created_source, created_by, started_at, detected_at, created_at, updated_at, lock_version)"
                        + " SELECT ?, id, '短链接跳转明显变慢', '跳转变慢', 'CREATED', 'MANUAL', 'demo-user', UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 0 FROM managed_system",
                KEY);
        incidentId = jdbc.queryForObject("SELECT id FROM incident WHERE incident_key = ?", Long.class, KEY);
    }

    /** Start 一次提交完成：唯一 Investigation 的 run 1、Incident 迁移、时间线；派发发生在提交之后。 */
    @Test
    void startCreatesRunOneAtomicallyAndDispatchesAfterCommit() throws Exception {
        List<String> statusSeenByDispatch = new ArrayList<>();
        doAnswer(invocation -> {
                    // 另一线程（另一连接）只能读到已提交数据
                    statusSeenByDispatch.add(CompletableFuture.supplyAsync(() -> jdbc.queryForObject(
                                    "SELECT status FROM incident WHERE id = ?", String.class, incidentId))
                            .get(10, TimeUnit.SECONDS));
                    return null;
                })
                .when(dispatcher)
                .dispatchInvestigation(anyLong(), anyInt());

        InvestigationRunResult result = service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user"));

        assertThat(result)
                .isEqualTo(new InvestigationRunResult(new IncidentKey(KEY), IncidentStatus.INVESTIGATING, 1, 1, false));
        verify(dispatcher).dispatchInvestigation(incidentId, 1);
        assertThat(statusSeenByDispatch).containsExactly("INVESTIGATING");
        assertThat(jdbc.queryForMap(
                        "SELECT current_run_no, current_run_capability_count, capability_call_count,"
                                + " consecutive_ai_failure_count, stop_requested_at, stop_requested_by,"
                                + " max_capability_calls, max_duration_seconds, agent_step_timeout_seconds,"
                                + " max_consecutive_ai_failures, lock_version,"
                                + " DATE_FORMAT(current_run_started_at, '%H:%i:%s.%f') AS run_started"
                                + " FROM investigation WHERE incident_id = ?",
                        incidentId))
                .containsAllEntriesOf(Map.of(
                        "current_run_no", 1L,
                        "current_run_capability_count", 0L,
                        "capability_call_count", java.math.BigInteger.ZERO,
                        "consecutive_ai_failure_count", 0L,
                        "max_capability_calls", 7L,
                        "max_duration_seconds", 480L,
                        "agent_step_timeout_seconds", 60L,
                        "max_consecutive_ai_failures", 3L,
                        "run_started", "08:00:00.250000"))
                .containsEntry("stop_requested_at", null)
                .containsEntry("stop_requested_by", null);
        assertThat(jdbc.queryForMap(
                        "SELECT event_type, actor_type, actor_id, summary, payload->>'$.source' AS source,"
                                + " payload->>'$.previousRunNo' AS previous_run, payload->>'$.runNo' AS run_no,"
                                + " payload->>'$.maxCapabilityCalls' AS max_calls FROM incident_timeline_event"
                                + " WHERE incident_id = ?",
                        incidentId))
                .containsAllEntriesOf(Map.of(
                        "event_type", "INVESTIGATION_STARTED",
                        "actor_type", "USER",
                        "actor_id", "demo-user",
                        "summary", "开始调查（第 1 轮）",
                        "source", "START_INVESTIGATION",
                        "previous_run", "0",
                        "run_no", "1",
                        "max_calls", "7"));
    }

    @Test
    void timelineFailureRollsBackStartWithoutDispatch() {
        doThrow(new IllegalStateException("timeline down")).when(timeline).append(any());

        assertThatThrownBy(() -> service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user")))
                .isInstanceOf(IllegalStateException.class);

        assertUntouched();
        verifyNoInteractions(dispatcher);
    }

    @Test
    void staleVersionUnknownKeyAndWrongStateAreRejectedWithoutWrites() {
        assertRejected(
                () -> service.startInvestigation(new StartInvestigationCommand(KEY, 3, "demo-user")),
                ErrorCode.INCIDENT_VERSION_CONFLICT);
        assertRejected(
                () -> service.startInvestigation(new StartInvestigationCommand("INC-20260927-0999", 0, "demo-user")),
                ErrorCode.INCIDENT_NOT_FOUND);
        assertRejected(
                () -> service.startInvestigation(new StartInvestigationCommand("inc-20260927-0001", 0, "demo-user")),
                ErrorCode.INCIDENT_NOT_FOUND);
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED' WHERE id = ?", incidentId);
        assertRejected(
                () -> service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user")),
                ErrorCode.INCIDENT_STATE_CONFLICT);
        verifyNoInteractions(dispatcher);
    }

    /** 两个同时的 Start 按 Incident 行锁串行，只有一个被准入，只存在一条 Investigation 与一次派发。 */
    @Test
    void concurrentStartsAdmitExactlyOne() throws Exception {
        List<Object> outcomes =
                race(() -> service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user")));

        assertThat(outcomes)
                .filteredOn(InvestigationRunResult.class::isInstance)
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(OpsPilotException.class::isInstance)
                .singleElement()
                .satisfies(ex -> assertThat(((OpsPilotException) ex).errorCode())
                        .isIn(ErrorCode.INCIDENT_STATE_CONFLICT, ErrorCode.INCIDENT_VERSION_CONFLICT));
        assertThat(count("investigation")).isEqualTo(1);
        assertThat(count("incident_timeline_event")).isEqualTo(1);
        verify(dispatcher).dispatchInvestigation(incidentId, 1);
    }

    /**
     * Continue 复用同一 Investigation 进入 run 2：只重置本轮起点、本轮计数、连续 AI 失败与 Stop；
     * 首次开始时间、累计计数、限制快照与既有时间线保留（01 §9、08 TASK-017）。
     */
    @Test
    void continueStartsNextRunOnSameInvestigationKeepingHistory() {
        service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user"));
        long investigationId = jdbc.queryForObject("SELECT id FROM investigation", Long.class);
        // run 1 用尽额度、已 Stop、已有 AI 失败，并已收束为 DIAGNOSED；限制快照与当前配置（7）不同
        jdbc.update("UPDATE investigation SET current_run_capability_count = 7, capability_call_count = 19,"
                + " consecutive_ai_failure_count = 2, stop_requested_at = UTC_TIMESTAMP(3),"
                + " stop_requested_by = 'demo-user', max_capability_calls = 12, started_at = '2026-09-27 07:00:00.000',"
                + " lock_version = 4");
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED', lock_version = 3");

        InvestigationRunResult result =
                service.continueInvestigation(new ContinueInvestigationCommand(KEY, 3, "demo-user"));

        assertThat(result)
                .isEqualTo(new InvestigationRunResult(new IncidentKey(KEY), IncidentStatus.INVESTIGATING, 4, 2, false));
        verify(dispatcher).dispatchInvestigation(incidentId, 2);
        assertThat(jdbc.queryForMap("SELECT id, current_run_no, current_run_capability_count, capability_call_count,"
                        + " consecutive_ai_failure_count, stop_requested_at, stop_requested_by,"
                        + " max_capability_calls, lock_version,"
                        + " DATE_FORMAT(started_at, '%H:%i:%s.%f') AS started,"
                        + " DATE_FORMAT(current_run_started_at, '%H:%i:%s.%f') AS run_started"
                        + " FROM investigation"))
                .containsAllEntriesOf(Map.of(
                        "id",
                        java.math.BigInteger.valueOf(investigationId),
                        "current_run_no",
                        2L,
                        "current_run_capability_count",
                        0L,
                        "capability_call_count",
                        java.math.BigInteger.valueOf(19),
                        "consecutive_ai_failure_count",
                        0L,
                        "max_capability_calls",
                        12L,
                        "lock_version",
                        java.math.BigInteger.valueOf(5),
                        "started",
                        "07:00:00.000000",
                        "run_started",
                        "08:00:00.250000"))
                .containsEntry("stop_requested_at", null)
                .containsEntry("stop_requested_by", null);
        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(payload->>'$.source', ':', payload->>'$.previousRunNo', '->',"
                                + " payload->>'$.runNo', ':', payload->>'$.maxCapabilityCalls')"
                                + " FROM incident_timeline_event ORDER BY id",
                        String.class))
                .containsExactly("START_INVESTIGATION:0->1:7", "CONTINUE_INVESTIGATION:1->2:12");
    }

    /** 等待审批（存在 PENDING Approval）时拒绝为 PENDING_APPROVAL_EXISTS；其他非 DIAGNOSED 状态与旧版本拒绝，均无写入。 */
    @Test
    void continueIsRejectedWhilePendingApprovalOrOutsideDiagnosed() {
        service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user"));
        jdbc.update("UPDATE incident SET status = 'AWAITING_APPROVAL', lock_version = 5");

        assertContinueRejected(99, ErrorCode.PENDING_APPROVAL_EXISTS);
        jdbc.update("UPDATE incident SET status = 'INVESTIGATING'");
        assertContinueRejected(5, ErrorCode.INCIDENT_STATE_CONFLICT);
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED'");
        assertContinueRejected(4, ErrorCode.INCIDENT_VERSION_CONFLICT);
        verify(dispatcher).dispatchInvestigation(incidentId, 1);
    }

    /** 两个同时的 Continue 只开启一个新 run（run 2，而不是 3）。 */
    @Test
    void concurrentContinuesAdmitExactlyOne() throws Exception {
        service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user"));
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED', lock_version = 2");

        List<Object> outcomes =
                race(() -> service.continueInvestigation(new ContinueInvestigationCommand(KEY, 2, "demo-user")));

        assertThat(outcomes)
                .filteredOn(InvestigationRunResult.class::isInstance)
                .hasSize(1);
        assertThat(outcomes).filteredOn(OpsPilotException.class::isInstance).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT current_run_no FROM investigation", Integer.class))
                .isEqualTo(2);
        assertThat(count("investigation")).isEqualTo(1);
        assertThat(count("incident_timeline_event")).isEqualTo(2);
        verify(dispatcher).dispatchInvestigation(incidentId, 2);
    }

    void assertContinueRejected(long expectedVersion, ErrorCode code) {
        assertThatThrownBy(() -> service.continueInvestigation(
                        new ContinueInvestigationCommand(KEY, expectedVersion, "demo-user")))
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(code));
        assertThat(jdbc.queryForObject("SELECT current_run_no FROM investigation", Integer.class))
                .isEqualTo(1);
        assertThat(count("incident_timeline_event")).isEqualTo(1);
    }

    /** 同时执行同一操作两次，返回各自的结果或异常。 */
    List<Object> race(java.util.concurrent.Callable<Object> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    try {
                        return action.call();
                    } catch (OpsPilotException ex) {
                        return ex;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    void assertUntouched() {
        assertThat(jdbc.queryForMap("SELECT status, lock_version FROM incident WHERE id = ?", incidentId))
                .containsEntry("status", "CREATED")
                .containsEntry("lock_version", java.math.BigInteger.ZERO);
        assertThat(count("investigation")).isZero();
        assertThat(count("incident_timeline_event")).isZero();
    }

    void assertRejected(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(code));
        assertThat(count("investigation")).isZero();
        assertThat(count("incident_timeline_event")).isZero();
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
