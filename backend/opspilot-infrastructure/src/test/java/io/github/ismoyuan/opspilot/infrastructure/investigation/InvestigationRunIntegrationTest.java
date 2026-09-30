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
import io.github.ismoyuan.opspilot.application.incident.CancelIncidentCommand;
import io.github.ismoyuan.opspilot.application.incident.CancelIncidentResult;
import io.github.ismoyuan.opspilot.application.incident.IncidentApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.ContinueInvestigationCommand;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationRunResult;
import io.github.ismoyuan.opspilot.application.investigation.StartInvestigationCommand;
import io.github.ismoyuan.opspilot.application.investigation.StopInvestigationCommand;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证开始/继续调查（08 TASK-016～017、01 §9、05 §24/§28）：唯一 Investigation 与 run 字段、
 * 条件迁移与时间线同事务、提交后才派发、冲突与并发只准入一个。WorkDispatcher 为测试替身。
 */
@SpringBootTest(properties = "opspilot.investigation.max-capability-calls=7")
@Testcontainers
@Import({
    InvestigationApplicationService.class,
    IncidentApplicationService.class,
    InvestigationRunIntegrationTest.FixedClock.class
})
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

    @Autowired
    IncidentApplicationService incidentService;

    @MockitoBean
    WorkDispatcher dispatcher;

    @MockitoSpyBean
    TimelineRepository timeline;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    long incidentId;

    @BeforeEach
    void resetData() {
        for (String table : List.of(
                "approval_request",
                "remediation_action",
                "remediation_plan",
                "diagnosis",
                "incident_timeline_event",
                "investigation",
                "incident",
                "managed_resource",
                "managed_system")) {
            jdbc.update("DELETE FROM " + table);
        }
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
     * 不同 Incident 的首次 Start 互不阻塞（TASK-016 修复，TASK-039 修复冒烟发现的死锁）：A 的 Start 已写入 Investigation 但未提交时，
     * B 的首次 Start 不必等它提交即可完成。原实现对尚不存在的 Investigation 加锁读取，在 uk_investigation_incident 上留下间隙锁，
     * 另一 Incident 的插入须等其提交，两者并发时互相等待成死锁。
     */
    @Test
    void firstStartsOfDifferentIncidentsDoNotBlockEachOther() throws Exception {
        String otherKey = "INC-20260927-0002";
        jdbc.update(
                "INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status,"
                        + " created_source, created_by, started_at, detected_at, created_at, updated_at, lock_version)"
                        + " SELECT ?, id, '统计数据延迟', '统计停止更新', 'CREATED', 'MANUAL', 'demo-user', UTC_TIMESTAMP(3),"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 0 FROM managed_system",
                otherKey);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        // 外层事务未提交：Start 加入该事务，Investigation 已插入但仍持有锁
        CompletableFuture<InvestigationRunResult> first =
                CompletableFuture.supplyAsync(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    InvestigationRunResult result =
                            service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user"));
                    written.countDown();
                    await(commit);
                    return result;
                }));
        assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<InvestigationRunResult> other = CompletableFuture.supplyAsync(
                () -> service.startInvestigation(new StartInvestigationCommand(otherKey, 0, "demo-user")));
        try {
            assertThat(other.get(5, TimeUnit.SECONDS).status()).isEqualTo(IncidentStatus.INVESTIGATING);
        } finally {
            commit.countDown();
        }

        assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(count("investigation")).isEqualTo(2);
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

    /**
     * 首次 Stop 同事务写停止时间/身份、Incident 与 Investigation 版本各加一、一条停止事件；同一活跃 run 的重复 Stop
     * （含带旧版本的重试）返回既有接受结果，不再写事件或版本（05 §27、08 TASK-018）。
     */
    @Test
    void firstStopIsRecordedOnceAndRepeatedStopIsIdempotent() {
        service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user"));
        InvestigationRunResult stopped =
                new InvestigationRunResult(new IncidentKey(KEY), IncidentStatus.INVESTIGATING, 2, 1, true);

        assertThat(service.stopInvestigation(new StopInvestigationCommand(KEY, 1, "demo-user")))
                .isEqualTo(stopped);
        assertThat(service.stopInvestigation(new StopInvestigationCommand(KEY, 1, "demo-user")))
                .isEqualTo(stopped);
        assertThat(service.stopInvestigation(new StopInvestigationCommand(KEY, 2, "other-user")))
                .isEqualTo(stopped);

        assertThat(jdbc.queryForMap(
                        "SELECT i.status, i.lock_version AS incident_version, v.lock_version AS investigation_version,"
                                + " v.current_run_no, v.stop_requested_by,"
                                + " DATE_FORMAT(v.stop_requested_at, '%H:%i:%s.%f') AS stop_at"
                                + " FROM incident i JOIN investigation v ON v.incident_id = i.id"))
                .containsAllEntriesOf(Map.of(
                        "status",
                        "INVESTIGATING",
                        "incident_version",
                        java.math.BigInteger.TWO,
                        "investigation_version",
                        java.math.BigInteger.ONE,
                        "current_run_no",
                        1L,
                        "stop_requested_by",
                        "demo-user",
                        "stop_at",
                        "08:00:00.250000"));
        assertThat(jdbc.queryForList(
                        "SELECT CONCAT(event_type, ':', actor_id, ':', payload->>'$.runNo')"
                                + " FROM incident_timeline_event ORDER BY id",
                        String.class))
                .containsExactly("INVESTIGATION_STARTED:demo-user:1", "INVESTIGATION_STOP_REQUESTED:demo-user:1");
        verify(dispatcher).dispatchInvestigation(incidentId, 1);
    }

    /** 未在调查中按状态冲突拒绝；尚未 Stop 时以旧版本请求按版本冲突拒绝；均无写入。 */
    @Test
    void stopRequiresInvestigatingAndCurrentVersion() {
        assertThatThrownBy(() -> service.stopInvestigation(new StopInvestigationCommand(KEY, 0, "demo-user")))
                .isInstanceOfSatisfying(OpsPilotException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCIDENT_STATE_CONFLICT);
                    assertThat(ex.details()).containsEntry("currentStatus", "CREATED");
                });
        service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user"));

        assertThatThrownBy(() -> service.stopInvestigation(new StopInvestigationCommand(KEY, 0, "demo-user")))
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCIDENT_VERSION_CONFLICT));
        assertThat(jdbc.queryForMap("SELECT stop_requested_at, lock_version FROM investigation"))
                .containsEntry("stop_requested_at", null)
                .containsEntry("lock_version", java.math.BigInteger.ZERO);
        assertThat(count("incident_timeline_event")).isEqualTo(1);
    }

    /** 状态机允许的来源取消为 CANCELLED，迁移与 INCIDENT_CANCELLED 时间线同事务（05 §33、08 TASK-019）。 */
    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "INVESTIGATING", "DIAGNOSED"})
    void cancelFromAllowedSourceRecordsTimeline(String source) {
        jdbc.update("UPDATE incident SET status = ?, lock_version = 4", source);

        CancelIncidentResult result =
                incidentService.cancelIncident(new CancelIncidentCommand(KEY, 4, "  确认是测试数据，停止处理。 ", "demo-user"));

        assertThat(result).isEqualTo(new CancelIncidentResult(new IncidentKey(KEY), IncidentStatus.CANCELLED, 5));
        assertThat(jdbc.queryForMap(
                        "SELECT event_type, actor_id, summary, payload->>'$.previousStatus' AS previous_status,"
                                + " payload->>'$.reason' AS reason FROM incident_timeline_event"))
                .containsAllEntriesOf(Map.of(
                        "event_type", "INCIDENT_CANCELLED",
                        "actor_id", "demo-user",
                        "summary", "取消故障处理：确认是测试数据，停止处理。",
                        "previous_status", source,
                        "reason", "确认是测试数据，停止处理。"));
    }

    /**
     * 不允许的来源被拒；时间线失败时回滚；等待审批时占位端口失败使整笔取消回滚，不宣称已撤销审批（TASK-067 补真实实现）。
     */
    @Test
    void cancelIsRejectedOrRolledBackWithoutPartialWrites() {
        jdbc.update("UPDATE incident SET status = 'EXECUTING', lock_version = 6");
        assertThatThrownBy(() -> incidentService.cancelIncident(new CancelIncidentCommand(KEY, 6, null, "demo-user")))
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INCIDENT_STATE_CONFLICT));

        jdbc.update("UPDATE incident SET status = 'DIAGNOSED'");
        doThrow(new IllegalStateException("timeline down")).when(timeline).append(any());
        assertThatThrownBy(() -> incidentService.cancelIncident(new CancelIncidentCommand(KEY, 6, null, "demo-user")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("timeline down");
        org.mockito.Mockito.reset(timeline);

        // 等待审批却没有 PENDING Approval 属数据不一致：取消整体回滚，不留下半套写入（TASK-062）
        jdbc.update("UPDATE incident SET status = 'AWAITING_APPROVAL'");
        assertThatThrownBy(() -> incidentService.cancelIncident(new CancelIncidentCommand(KEY, 6, null, "demo-user")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a pending approval");

        assertThat(jdbc.queryForMap("SELECT status, lock_version FROM incident"))
                .containsEntry("status", "AWAITING_APPROVAL")
                .containsEntry("lock_version", java.math.BigInteger.valueOf(6));
        assertThat(count("incident_timeline_event")).isZero();
    }

    /**
     * 08 TASK-062、05 §33：取消等待审批的 Incident 在同一事务把 PENDING Approval（记录取消者与时间、版本加一）与未执行 Plan 置为 CANCELLED；
     * 已执行的 Plan 与其他 Incident 的方案不受影响。
     */
    @Test
    void cancellingWhileAwaitingApprovalWithdrawsThePendingApprovalAndPlans() {
        service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user"));
        long pendingPlan = plan(incidentId, "ACTIVE", "PENDING");
        long executedPlan = plan(incidentId, "EXECUTED", "APPROVED");
        long otherIncident = otherIncident();
        long otherPlan = plan(otherIncident, "ACTIVE", "PENDING");
        jdbc.update("UPDATE incident SET status = 'AWAITING_APPROVAL', lock_version = 6 WHERE id = ?", incidentId);

        incidentService.cancelIncident(new CancelIncidentCommand(KEY, 6, "不再处理", "demo-user"));

        assertThat(jdbc.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, incidentId))
                .isEqualTo("CANCELLED");
        assertThat(planState(pendingPlan)).isEqualTo("CANCELLED/CANCELLED/demo-user/1");
        assertThat(planState(executedPlan)).isEqualTo("EXECUTED/APPROVED/demo-user/0");
        assertThat(planState(otherPlan)).isEqualTo("ACTIVE/PENDING/-/0");
    }

    /** 05 §28：同一行锁下以真实 Approval 核对——即使 Incident 标为 DIAGNOSED，仍有 PENDING Approval 时拒绝继续调查，不写入任何数据。 */
    @Test
    void continueIsRejectedWhileARealPendingApprovalExists() {
        service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user"));
        plan(incidentId, "ACTIVE", "PENDING");
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED', lock_version = 5 WHERE id = ?", incidentId);

        assertContinueRejected(5, ErrorCode.PENDING_APPROVAL_EXISTS);

        assertThat(jdbc.queryForObject("SELECT current_run_no FROM investigation", Integer.class))
                .isOne();
    }

    /** 同时发起的 Cancel 与 Start 在 Incident 行锁上串行，只有一个成功。 */
    @Test
    void concurrentCancelAndStartAdmitExactlyOne() throws Exception {
        List<Object> outcomes = race(
                () -> incidentService.cancelIncident(new CancelIncidentCommand(KEY, 0, null, "demo-user")),
                () -> service.startInvestigation(new StartInvestigationCommand(KEY, 0, "demo-user")));

        assertThat(outcomes).filteredOn(OpsPilotException.class::isInstance).hasSize(1);
        String status = jdbc.queryForObject("SELECT status FROM incident", String.class);
        assertThat(status).isIn("CANCELLED", "INVESTIGATING");
        assertThat(jdbc.queryForObject("SELECT lock_version FROM incident", Integer.class))
                .isEqualTo(1);
        assertThat(count("incident_timeline_event")).isEqualTo(1);
        assertThat(count("investigation")).isEqualTo(status.equals("INVESTIGATING") ? 1 : 0);
    }

    /** 同时执行同一操作两次，返回各自的结果或异常。 */
    List<Object> race(java.util.concurrent.Callable<Object> action) throws Exception {
        return race(action, action);
    }

    /** 同时执行两个操作，返回各自的结果或异常。 */
    List<Object> race(java.util.concurrent.Callable<Object> first, java.util.concurrent.Callable<Object> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<Object> action : List.of(first, second)) {
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
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

    /** 为该 Incident 建一个 Plan（附 Action 与给定状态的 Approval）；Diagnosis 与目标资源按需创建。 */
    private long plan(long incident, String planStatus, String approvalStatus) {
        jdbc.update("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) SELECT id, 'statistics-consumer', 'C', 'CONSUMER', 'ACTIVE',"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM managed_system WHERE NOT EXISTS (SELECT 1 FROM"
                + " managed_resource WHERE resource_key = 'statistics-consumer')");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM investigation WHERE incident_id = ?", Integer.class, incident)
                == 0) {
            jdbc.update(
                    "INSERT INTO investigation (incident_id, started_at, last_activity_at, current_run_no,"
                            + " current_run_started_at, max_capability_calls, max_duration_seconds,"
                            + " agent_step_timeout_seconds, max_consecutive_ai_failures, created_at, updated_at) VALUES"
                            + " (?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 1, UTC_TIMESTAMP(3), 12, 480, 60, 3,"
                            + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                    incident);
        }
        jdbc.update(
                "INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, summary, impact_summary,"
                        + " created_at) SELECT id, 1, (SELECT COUNT(*) + 1 FROM diagnosis d WHERE d.investigation_id ="
                        + " i.id), 'UNDETERMINED', 'S', 'I', UTC_TIMESTAMP(3) FROM investigation i WHERE incident_id = ?",
                incident);
        jdbc.update(
                "INSERT INTO remediation_plan (incident_id, diagnosis_id, title, summary, status, created_at,"
                        + " updated_at) SELECT ?, MAX(d.id), 'T', 'S', ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)"
                        + " FROM diagnosis d JOIN investigation i ON i.id = d.investigation_id WHERE i.incident_id = ?",
                incident,
                planStatus,
                incident);
        long plan = jdbc.queryForObject("SELECT MAX(id) FROM remediation_plan", Long.class);
        jdbc.update(
                "INSERT INTO remediation_action (remediation_plan_id, capability_key, target_resource_id,"
                        + " parameter_schema_name, parameter_schema_version, parameter_payload, summary,"
                        + " expected_impact_summary, risk_level, requires_approval, created_at) SELECT ?,"
                        + " 'service.restart', id, 'service.restart.request', 1, '{}', 'S', 'E', 'MEDIUM', TRUE,"
                        + " UTC_TIMESTAMP(3) FROM managed_resource WHERE resource_key = 'statistics-consumer'",
                plan);
        boolean pending = approvalStatus.equals("PENDING");
        jdbc.update(
                "INSERT INTO approval_request (remediation_action_id, status, requested_at, decided_by, decided_at,"
                        + " created_at, updated_at) SELECT id, ?, UTC_TIMESTAMP(3), ?, "
                        + (pending ? "NULL" : "UTC_TIMESTAMP(3)")
                        + ", UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM remediation_action WHERE remediation_plan_id = ?",
                approvalStatus,
                pending ? null : "demo-user",
                plan);
        return plan;
    }

    private long otherIncident() {
        jdbc.update("INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status,"
                + " created_source, created_by, started_at, detected_at, created_at, updated_at, lock_version)"
                + " SELECT 'INC-20260930-0099', id, 'T', 'I', 'AWAITING_APPROVAL', 'MANUAL', 'demo-user',"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 0 FROM managed_system");
        return jdbc.queryForObject("SELECT id FROM incident WHERE incident_key = 'INC-20260930-0099'", Long.class);
    }

    /** Plan 状态 / Approval 状态 / 决定人（无则 -）/ Approval 版本。 */
    private String planState(long plan) {
        return jdbc.queryForObject(
                "SELECT CONCAT(p.status, '/', a.status, '/', COALESCE(a.decided_by, '-'), '/', a.lock_version)"
                        + " FROM remediation_plan p JOIN remediation_action ra ON ra.remediation_plan_id = p.id"
                        + " JOIN approval_request a ON a.remediation_action_id = ra.id WHERE p.id = ?",
                String.class,
                plan);
    }
}
