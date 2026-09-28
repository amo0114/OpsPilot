package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.dispatch.InvestigationWorker;
import io.github.ismoyuan.opspilot.application.dispatch.StartupRecoveryCoordinator;
import io.github.ismoyuan.opspilot.application.investigation.InvestigationApplicationService;
import io.github.ismoyuan.opspilot.application.investigation.StartInvestigationCommand;
import java.util.List;
import java.util.Map;
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
 * 真实 MySQL＋真实 InProcessWorkDispatcher（08 TASK-035～036、07 §51）：已提交但未被唤醒的 INVESTIGATING 由启动恢复按数据库中的
 * 原 run 唤醒且不改任何运行控制字段；其他状态不派发；提交后经线程池唤醒 Worker；周期补派发与正在运行的 Worker 合并。
 * InvestigationWorker 为测试替身（调查循环属 TASK-037～043）。
 */
@SpringBootTest
@Testcontainers
@Import({StartupRecoveryCoordinator.class, InvestigationApplicationService.class, ClockConfiguration.class})
class DispatchRecoveryIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    StartupRecoveryCoordinator coordinator;

    @Autowired
    InvestigationApplicationService investigations;

    @MockitoBean
    InvestigationWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    DispatchSeed seed;

    @BeforeEach
    void reset() {
        seed = new DispatchSeed(jdbc);
    }

    /** 提交后、派发前崩溃：启动扫描按原 run 唤醒（含已 Stop 的 run，以便收束），不刷新 run/预算/Stop/版本（07 §51～§53）。 */
    @Test
    void startupRecoveryWakesCommittedRunsExactlyAsStored() {
        long running = seed.incident("INVESTIGATING");
        seed.investigation(running, 3, false);
        long stopped = seed.incident("INVESTIGATING");
        seed.investigation(stopped, 1, true);
        long diagnosed = seed.incident("DIAGNOSED");
        seed.investigation(diagnosed, 2, false);
        long created = seed.incident("CREATED");
        List<Map<String, Object>> before = seed.snapshot();

        assertThat(coordinator.recoverAfterStartup()).isEqualTo(2);

        verify(worker, timeout(5_000)).runInvestigation(running, 3);
        verify(worker, timeout(5_000)).runInvestigation(stopped, 1);
        verify(worker, after(300).never()).runInvestigation(eq(diagnosed), anyInt());
        verify(worker, never()).runInvestigation(eq(created), anyInt());
        assertThat(seed.snapshot()).isEqualTo(before);
    }

    /** 提交后立即唤醒：Start 的 afterCommit 经真实线程池到达 Worker，run 为 1（08 TASK-035）。 */
    @Test
    void startInvestigationWakesTheWorkerAfterCommit() {
        long incident = seed.incident("CREATED");
        jdbc.update("UPDATE incident SET lock_version = 0 WHERE id = ?", incident);
        String key = jdbc.queryForObject("SELECT incident_key FROM incident WHERE id = ?", String.class, incident);

        investigations.startInvestigation(new StartInvestigationCommand(key, 0, "demo-user"));

        verify(worker, timeout(5_000)).runInvestigation(incident, 1);
    }

    /** 周期补派发只唤醒没有 Worker 的工作：正在运行的同一 run 被合并，不会并发第二个 Worker（07 §48、§51）。 */
    @Test
    void rescanDoesNotStartASecondWorkerForRunningWork() throws Exception {
        long incident = seed.incident("INVESTIGATING");
        seed.investigation(incident, 2, false);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    running.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return null;
                })
                .when(worker)
                .runInvestigation(incident, 2);

        coordinator.recoverAfterStartup();
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        coordinator.redispatchPending();
        coordinator.redispatchPending();
        verify(worker, after(300).times(1)).runInvestigation(incident, 2);

        release.countDown();
        // 释放后没有 Worker：下一次补派发重新唤醒同一 run
        coordinator.redispatchPending();
        verify(worker, timeout(5_000).times(2)).runInvestigation(incident, 2);
    }
}
