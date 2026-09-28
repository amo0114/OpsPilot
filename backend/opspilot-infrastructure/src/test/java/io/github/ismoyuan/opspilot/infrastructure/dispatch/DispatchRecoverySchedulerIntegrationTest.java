package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.dispatch.InvestigationWorker;
import io.github.ismoyuan.opspilot.application.dispatch.StartupRecoveryCoordinator;
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
 * 开启自动恢复时的 Spring 装配（07 §51）：应用就绪后周期补派发按配置间隔运行，存活期间唤醒此前未被唤醒的已提交工作
 * （例如线程池拒绝或派发前丢失）。
 */
@SpringBootTest(
        properties = {
            "opspilot.dispatcher.recovery-enabled=true",
            "opspilot.dispatcher.recovery-scan-interval-seconds=1"
        })
@Testcontainers
@Import({StartupRecoveryCoordinator.class, ClockConfiguration.class})
class DispatchRecoverySchedulerIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @MockitoBean
    InvestigationWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void periodicRescanWakesWorkCommittedWhileRunning() {
        DispatchSeed seed = new DispatchSeed(jdbc);
        long incident = seed.incident("INVESTIGATING");
        seed.investigation(incident, 4, false);

        verify(worker, timeout(5_000).atLeastOnce()).runInvestigation(incident, 4);
    }
}
