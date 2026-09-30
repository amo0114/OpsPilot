package io.github.ismoyuan.opspilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.dispatch.ActionExecutionWorker;
import io.github.ismoyuan.opspilot.application.dispatch.InvestigationWorker;
import io.github.ismoyuan.opspilot.application.dispatch.RecoveryVerificationWorker;
import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.execution.ActionExecutionService;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryVerificationService;
import io.github.ismoyuan.opspilot.infrastructure.dispatch.InProcessWorkDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 正式应用装配（B29-R1 回归）：真实 MySQL、完整组件扫描，不以替身替换派发器或任何 Worker。派发器创建时取得执行 Worker，执行成功
 * 事务又会创建 Verification 并在提交后派发——这条链必须能在同一上下文中装配，否则应用无法启动；其他契约测试替换了派发器，覆盖不到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApplicationWiringTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    WorkDispatcher dispatcher;

    @Autowired
    InvestigationWorker investigations;

    @Autowired
    ActionExecutionWorker executions;

    @Autowired
    RecoveryVerificationWorker verifications;

    /** 应用以真实派发器与真实 Worker 启动（上下文加载即证明没有创建环）。 */
    @Test
    void theRealDispatcherStartsWithTheRealWorkers() {
        assertThat(dispatcher).isInstanceOf(InProcessWorkDispatcher.class);
        assertThat(executions).isInstanceOf(ActionExecutionService.class);
        assertThat(verifications).isInstanceOf(RecoveryVerificationService.class);
        assertThat(investigations.getClass().getSimpleName()).doesNotStartWith("Unwired");
    }
}
