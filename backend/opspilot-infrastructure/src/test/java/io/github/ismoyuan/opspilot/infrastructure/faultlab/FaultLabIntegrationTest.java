package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ClockConfiguration;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.faultlab.FaultCause;
import io.github.ismoyuan.opspilot.application.faultlab.FaultExperimentInterruptionRecorder;
import io.github.ismoyuan.opspilot.application.faultlab.FaultExperimentRepository;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.application.faultlab.FaultLabApplicationService;
import io.github.ismoyuan.opspilot.application.faultlab.FaultScenarioCatalog;
import io.github.ismoyuan.opspilot.application.faultlab.InjectFaultCommand;
import io.github.ismoyuan.opspilot.application.faultlab.InjectFaultResult;
import io.github.ismoyuan.opspilot.application.faultlab.ResetFaultResult;
import io.github.ismoyuan.opspilot.application.faultlab.evaluation.FaultEvaluationService;
import io.github.ismoyuan.opspilot.application.incident.IncidentApplicationService;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.application.system.ManagedSystemRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.faultlab.FaultExperimentStatus;
import io.github.ismoyuan.opspilot.domain.incident.IncidentAction;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 08 TASK-090、TASK-092：真实 MySQL＋ShortLink demo Seed 上的 Fault Lab 生命周期。注入器为脚本替身（真实注入器属 TASK-093～095），
 * 用于证明控制流：确认生效才在同一事务创建 Incident 并标 ACTIVE、外部动作不在事务中、失败不留下 Incident、只对 DEMO/TEST 开放、同一系统
 * 同时只有一个进行中的实验、Reset 不改变 Incident、启动时收束中断的实验。mysql-slow-query 故意没有注入器。
 */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/demo")
@Testcontainers
@Import({
    FaultLabApplicationService.class,
    FaultScenarioCatalog.class,
    FaultEvaluationService.class,
    FaultExperimentInterruptionRecorder.class,
    IncidentApplicationService.class,
    ClockConfiguration.class,
    FaultLabIntegrationTest.Injectors.class
})
class FaultLabIntegrationTest {

    static final String SYSTEM = "shortlink-platform";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @TestConfiguration
    static class Injectors {

        @Bean
        ScriptedFaultInjector redisLatencyInjector() {
            return new ScriptedFaultInjector("redis-latency");
        }

        @Bean
        ScriptedFaultInjector consumerStopInjector() {
            return new ScriptedFaultInjector("statistics-consumer-stop");
        }
    }

    @Autowired
    FaultLabApplicationService faultLab;

    @Autowired
    FaultEvaluationService evaluation;

    @Autowired
    FaultExperimentInterruptionRecorder interruptions;

    @Autowired
    ScriptedFaultInjector redisLatencyInjector;

    @Autowired
    ScriptedFaultInjector consumerStopInjector;

    static final String STOPPED_CONTAINER = "0123456789abcdef".repeat(4);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FaultScenarioCatalog catalog;

    @Autowired
    FaultExperimentRepository experimentRepository;

    @Autowired
    ManagedSystemRepository systems;

    @Autowired
    ManagedResourceRepository resources;

    @Autowired
    IncidentApplicationService incidents;

    @Autowired
    SchemaCodecRegistry codecs;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    Clock clock;

    @BeforeEach
    void reset() {
        for (String table : List.of(
                "fault_experiment",
                "incident_timeline_event",
                "investigation",
                "incident_affected_resource",
                "incident")) {
            jdbc.update("DELETE FROM " + table);
        }
        redisLatencyInjector.clear();
        consumerStopInjector.clear();
        jdbc.update("UPDATE managed_system SET status = 'ACTIVE' WHERE system_key = ?", SYSTEM);
        system("prod-platform", "PRODUCTION");
        system("test-platform", "TEST");
    }

    /**
     * 确认生效后，CREATED Incident（FAULT_LAB、症状标题、started_at = 生效时间、detected_at = 确认时间、症状资源）与 ACTIVE 实验同时提交；
     * 注入与确认都不在数据库事务中；Ground Truth 写入实验并在确认生效时补上被停止的容器与停止时间（09 §63），只能经 Evaluation 读取。
     */
    @Test
    void aConfirmedInjectionCreatesTheIncidentWithTheActiveExperiment() {
        consumerStopInjector.stoppedContainerId = STOPPED_CONTAINER;
        InjectFaultResult result = inject("statistics-consumer-stop", SYSTEM);

        assertThat(result.status()).isEqualTo(FaultExperimentStatus.ACTIVE);
        assertThat(result.incident().status()).isEqualTo(IncidentStatus.CREATED);
        assertThat(result.incident().version()).isZero();
        assertThat(result.incident().availableActions())
                .containsExactly(IncidentAction.START_INVESTIGATION, IncidentAction.CANCEL_INCIDENT);
        assertThat(consumerStopInjector.calls)
                .containsExactly(
                        "inject:statistics-consumer-stop@shortlink-platform/statistics-consumer",
                        "verify:statistics-consumer-stop@shortlink-platform/statistics-consumer");
        assertThat(consumerStopInjector.transactionActive).containsOnly(false);

        Map<String, Object> incident = jdbc.queryForMap(
                "SELECT id, title, impact_summary, created_source, created_by, started_at, detected_at FROM incident"
                        + " WHERE incident_key = ?",
                result.incident().incidentKey().value());
        assertThat(incident)
                .containsEntry("title", "访问统计数据长时间未更新")
                .containsEntry("created_source", "FAULT_LAB")
                .containsEntry("created_by", "demo-user");
        Duration startToDetect = Duration.between(time(incident.get("started_at")), time(incident.get("detected_at")));
        assertThat(startToDetect).isBetween(Duration.ofMillis(3_900), Duration.ofMillis(4_100));
        assertThat(jdbc.queryForList(
                        "SELECT r.resource_key FROM incident_affected_resource a JOIN managed_resource r"
                                + " ON r.id = a.managed_resource_id WHERE a.incident_id = ?",
                        String.class,
                        incident.get("id")))
                .containsExactly("statistics-consumer");

        Map<String, Object> experiment = experiment(result.experimentId());
        assertThat(experiment)
                .containsEntry("status", "ACTIVE")
                .containsEntry("scenario_key", "statistics-consumer-stop")
                .containsEntry("resource_key", "statistics-consumer");
        assertThat(((Number) experiment.get("incident_id")).longValue())
                .isEqualTo(((Number) incident.get("id")).longValue());
        assertThat(time(experiment.get("injected_at"))).isEqualTo(time(incident.get("started_at")));
        FaultGroundTruthV1 groundTruth = evaluation.groundTruth(result.experimentId());
        assertThat(groundTruth.cause()).isEqualTo(FaultCause.STATISTICS_CONSUMER_STOPPED);
        assertThat(groundTruth.containerId()).isEqualTo(STOPPED_CONTAINER);
        assertThat(groundTruth.consumerStoppedAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(time(incident.get("started_at")).truncatedTo(ChronoUnit.MILLIS));
    }

    /** S1 确认生效时 Ground Truth 补上 Gate 实测（所达分支与真实数值，09 §33、ACC-S1-003），latencyMs 为实际注入值。 */
    @Test
    void aConfirmedRedisLatencyRecordsItsGateInTheGroundTruth() {
        FaultGroundTruthV1.RedisLatencyGate gate = new FaultGroundTruthV1.RedisLatencyGate(
                FaultGroundTruthV1.SymptomBranch.ERROR_RATE, 600, 603, 12, 640, 0.0, 0.4);
        redisLatencyInjector.redisLatencyGate = gate;

        InjectFaultResult result = inject("redis-latency", SYSTEM);

        FaultGroundTruthV1 groundTruth = evaluation.groundTruth(result.experimentId());
        assertThat(groundTruth.cause()).isEqualTo(FaultCause.REDIS_NETWORK_LATENCY);
        assertThat(groundTruth.latencyMs()).isEqualTo(600);
        assertThat(groundTruth.redisLatencyGate()).isEqualTo(gate);
        assertThat(groundTruth.containerId()).isNull();
    }

    /** S2 确认生效时 Ground Truth 补上 Gate 实测（实际配方、连接池/慢语句/HTTP 实测与分支，09 §51、ACC-S2-001～005）。 */
    @Test
    void aConfirmedMysqlSlowQueryRecordsItsGateInTheGroundTruth() {
        FaultGroundTruthV1.MysqlSlowQueryGate gate = new FaultGroundTruthV1.MysqlSlowQueryGate(
                FaultGroundTruthV1.SymptomBranch.BOTH, 8, 3000, 8, 6, 5, 40, 3000, 3002, 28, 12_000, 0.0, 0.2);
        ScriptedFaultInjector slowQuery = new ScriptedFaultInjector("mysql-slow-query");
        slowQuery.mysqlSlowQueryGate = gate;
        FaultLabApplicationService withSlowQuery = new FaultLabApplicationService(
                catalog,
                List.of(slowQuery),
                experimentRepository,
                systems,
                resources,
                incidents,
                codecs,
                transactionManager,
                clock);

        InjectFaultResult result =
                withSlowQuery.inject(new InjectFaultCommand("mysql-slow-query", SYSTEM, "demo-user"));

        FaultGroundTruthV1 groundTruth = evaluation.groundTruth(result.experimentId());
        assertThat(groundTruth.cause()).isEqualTo(FaultCause.MYSQL_SLOW_QUERY_POOL_EXHAUSTION);
        assertThat(groundTruth.mysqlSlowQueryGate()).isEqualTo(gate);
        assertThat(groundTruth.redisLatencyGate()).isNull();
    }

    /** 没有报告被停止容器的注入（及注入失败）保持插入时的 Ground Truth。 */
    @Test
    void theGroundTruthStaysAsInsertedWithoutInjectionFacts() {
        InjectFaultResult result = inject("statistics-consumer-stop", SYSTEM);

        assertThat(evaluation.groundTruth(result.experimentId()))
                .isEqualTo(FaultGroundTruthV1.of(FaultCause.STATISTICS_CONSUMER_STOPPED, null));
    }

    /** 未确认生效：FAILED 记录注入器给出的脱敏说明，没有任何 Incident，错误含实验 id。 */
    @Test
    void anUnconfirmedInjectionFailsWithoutAnIncident() {
        redisLatencyInjector.verifyFailure = new FaultInjectionException("Redis latency gate not reached");

        ApplicationException failed = injectFails("redis-latency", SYSTEM, ErrorCode.FAULT_INJECTION_FAILED);

        long experimentId = ((Number) failed.details().get("experimentId")).longValue();
        assertThat(failed.details()).containsEntry("reason", "INJECTION_NOT_CONFIRMED");
        assertThat(experiment(experimentId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_message", "Redis latency gate not reached")
                .containsEntry("incident_id", null);
        assertThat(incidentCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_timeline_event", Integer.class))
                .isZero();
    }

    /** 注入器的其他异常只记类型，消息（可能含凭据或端点）不进入库。 */
    @Test
    void unexpectedInjectorErrorsKeepOnlyTheirType() {
        redisLatencyInjector.injectFailure = new IllegalStateException("password=hunter2 tcp://10.0.0.5:8474");

        ApplicationException failed = injectFails("redis-latency", SYSTEM, ErrorCode.FAULT_INJECTION_FAILED);

        Map<String, Object> experiment = experiment(((Number) failed.details().get("experimentId")).longValue());
        assertThat(experiment).containsEntry("error_message", "Fault lab action failed: IllegalStateException");
        assertThat(redisLatencyInjector.calls).hasSize(1);
    }

    /** 已确认生效但 Incident 未能创建（确认期间系统被停用）：Incident 与 ACTIVE 一起回滚，实验 FAILED，可 Reset。 */
    @Test
    void anIncidentThatCannotBeCreatedLeavesAFailedExperiment() {
        redisLatencyInjector.onVerify =
                () -> jdbc.update("UPDATE managed_system SET status = 'DISABLED' WHERE system_key = ?", SYSTEM);

        ApplicationException failed = injectFails("redis-latency", SYSTEM, ErrorCode.FAULT_INJECTION_FAILED);

        assertThat(failed.details()).containsEntry("reason", "INCIDENT_NOT_CREATED");
        long experimentId = ((Number) failed.details().get("experimentId")).longValue();
        assertThat(experiment(experimentId)).containsEntry("status", "FAILED").containsEntry("incident_id", null);
        assertThat(incidentCount()).isZero();
        jdbc.update("UPDATE managed_system SET status = 'ACTIVE' WHERE system_key = ?", SYSTEM);
        assertThat(faultLab.reset(experimentId).status()).isEqualTo(FaultExperimentStatus.RESET);
    }

    /**
     * 09 §19（B33-R1 P2）：故障生效时间晚于确认时间（即使只晚 30 秒、在人工创建的 5 分钟容差内），或确认时间在未来，都不是已确认的注入：
     * FAILED、不创建 Incident。
     */
    @Test
    void injectionTimesMustBeOrdered() {
        redisLatencyInjector.startedAgo = Duration.ofSeconds(1);
        redisLatencyInjector.detectedAgo = Duration.ofSeconds(31);
        ApplicationException reversed = injectFails("redis-latency", SYSTEM, ErrorCode.FAULT_INJECTION_FAILED);
        assertThat(reversed.details()).containsEntry("reason", "INJECTION_TIMES_INVALID");
        assertThat(experiment(((Number) reversed.details().get("experimentId")).longValue()))
                .containsEntry("status", "FAILED")
                .containsEntry(
                        "error_message", "Injector reported a fault start after its confirmation or in the future");

        redisLatencyInjector.startedAgo = Duration.ofSeconds(5);
        redisLatencyInjector.detectedAgo = Duration.ofSeconds(-60);
        assertThat(injectFails("redis-latency", SYSTEM, ErrorCode.FAULT_INJECTION_FAILED)
                        .details())
                .containsEntry("reason", "INJECTION_TIMES_INVALID");
        assertThat(incidentCount()).isZero();

        redisLatencyInjector.detectedAgo = redisLatencyInjector.startedAgo;
        assertThat(inject("redis-latency", SYSTEM).status()).isEqualTo(FaultExperimentStatus.ACTIVE);
    }

    /** 只对 DEMO/TEST 开放：PRODUCTION 不创建实验、不调用注入器；TEST 允许。 */
    @Test
    void onlyDemoAndTestSystemsAreAllowed() {
        ApplicationException refused =
                injectFails("statistics-consumer-stop", "prod-platform", ErrorCode.FAULT_SCENARIO_NOT_ALLOWED);

        assertThat(refused.details()).containsEntry("reason", "ENVIRONMENT_NOT_ALLOWED");
        assertThat(consumerStopInjector.calls).isEmpty();
        // 环境先于注入器可用性（B33-R1 P2）：没有注入器的场景在 PRODUCTION 上同样是 422 而不是 502
        assertThat(injectFails("mysql-slow-query", "prod-platform", ErrorCode.FAULT_SCENARIO_NOT_ALLOWED)
                        .details())
                .containsEntry("reason", "ENVIRONMENT_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fault_experiment", Integer.class))
                .isZero();
        assertThat(inject("statistics-consumer-stop", "test-platform").status())
                .isEqualTo(FaultExperimentStatus.ACTIVE);
    }

    /**
     * 注入器只控制绑定的系统（B34-R1 P1）：另一个 TEST 系统即使资源同名也在创建实验之前得到 422，不调用注入器；已有实验所属系统不再受
     * 控制时 Reset 同样 422，状态不变、不调用 reset。
     */
    @Test
    void anInjectorOnlyControlsItsBoundSystem() {
        // 先在受控时留下 test-platform 上的 FAILED 实验，随后该系统不再受控
        consumerStopInjector.verifyFailure = new FaultInjectionException("Gate failed: not met");
        long failed = ((Number)
                        injectFails("statistics-consumer-stop", "test-platform", ErrorCode.FAULT_INJECTION_FAILED)
                                .details()
                                .get("experimentId"))
                .longValue();
        consumerStopInjector.verifyFailure = null;
        consumerStopInjector.calls.clear();
        consumerStopInjector.uncontrolledSystems = Set.of("test-platform");

        assertThat(injectFails("statistics-consumer-stop", "test-platform", ErrorCode.FAULT_SCENARIO_NOT_ALLOWED)
                        .details())
                .containsEntry("reason", "INJECTOR_NOT_BOUND_TO_SYSTEM");
        assertThat(assertThrows(() -> faultLab.reset(failed), ErrorCode.FAULT_SCENARIO_NOT_ALLOWED)
                        .details())
                .containsEntry("reason", "INJECTOR_NOT_BOUND_TO_SYSTEM");
        assertThat(consumerStopInjector.calls).isEmpty();
        assertThat(experiment(failed)).containsEntry("status", "FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fault_experiment", Integer.class))
                .isEqualTo(1);
        assertThat(incidentCount()).isZero();
        // 绑定的系统不受影响
        assertThat(inject("statistics-consumer-stop", SYSTEM).status()).isEqualTo(FaultExperimentStatus.ACTIVE);
    }

    /** 场景与系统不存在、场景所需资源不在该系统、场景没有注入器：都在创建实验之前拒绝。 */
    @Test
    void invalidRequestsAreRejectedBeforeAnyExperiment() {
        injectFails("disk-full", SYSTEM, ErrorCode.FAULT_SCENARIO_NOT_FOUND);
        injectFails("Redis-Latency", SYSTEM, ErrorCode.FAULT_SCENARIO_NOT_FOUND);
        injectFails("redis-latency", "missing-platform", ErrorCode.SYSTEM_NOT_FOUND);
        jdbc.update(
                "UPDATE managed_resource r JOIN managed_system s ON s.id = r.managed_system_id"
                        + " SET r.status = 'DISABLED' WHERE s.system_key = 'test-platform' AND r.resource_key = 'shortlink-redis'");
        assertThat(injectFails("redis-latency", "test-platform", ErrorCode.FAULT_SCENARIO_NOT_ALLOWED)
                        .details())
                .containsEntry("reason", "TARGET_RESOURCE_NOT_AVAILABLE");
        assertThat(injectFails("mysql-slow-query", SYSTEM, ErrorCode.FAULT_INJECTION_FAILED)
                        .details())
                .containsEntry("reason", "INJECTOR_NOT_AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fault_experiment", Integer.class))
                .isZero();
        assertThat(redisLatencyInjector.calls).isEmpty();
    }

    /** 注入进行中（INJECTING）或故障生效中（ACTIVE）时，同一系统的第二次注入冲突且不调用注入器；其他系统不受影响。 */
    @Test
    void oneExperimentAtATimePerSystem() throws Exception {
        consumerStopInjector.injectEntered = new CountDownLatch(1);
        consumerStopInjector.releaseInject = new CountDownLatch(1);
        CompletableFuture<InjectFaultResult> first =
                CompletableFuture.supplyAsync(() -> inject("statistics-consumer-stop", SYSTEM));
        assertThat(consumerStopInjector.injectEntered.await(5, TimeUnit.SECONDS))
                .isTrue();

        assertThat(injectFails("redis-latency", SYSTEM, ErrorCode.FAULT_EXPERIMENT_STATE_CONFLICT)
                        .details())
                .containsEntry("reason", "EXPERIMENT_IN_PROGRESS");
        consumerStopInjector.releaseInject.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(FaultExperimentStatus.ACTIVE);

        injectFails("redis-latency", SYSTEM, ErrorCode.FAULT_EXPERIMENT_STATE_CONFLICT);
        assertThat(redisLatencyInjector.calls).isEmpty();
        assertThat(inject("redis-latency", "test-platform").status()).isEqualTo(FaultExperimentStatus.ACTIVE);
    }

    /**
     * Reset 只恢复实验环境：ACTIVE → RESET 且写重置时间，Incident 状态与版本不变；外部动作不在事务中；已 RESET 再 Reset 冲突；恢复失败记
     * FAILED 并可重试；Reset 后同一系统可再次注入。
     */
    @Test
    void resetRestoresOnlyTheEnvironment() {
        InjectFaultResult injected = inject("statistics-consumer-stop", SYSTEM);
        long experimentId = injected.experimentId();
        jdbc.update("UPDATE incident SET status = 'INVESTIGATING', lock_version = 1");
        consumerStopInjector.resetFailure = new FaultInjectionException("Consumer did not start");

        assertThatThrownBy(() -> faultLab.reset(experimentId))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FAULT_RESET_FAILED));
        assertThat(experiment(experimentId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_message", "Consumer did not start");

        consumerStopInjector.resetFailure = null;
        assertThat(faultLab.reset(experimentId).status()).isEqualTo(FaultExperimentStatus.RESET);
        assertThat(experiment(experimentId).get("reset_at")).isNotNull();
        assertThat(consumerStopInjector.calls)
                .filteredOn(call -> call.startsWith("reset"))
                .hasSize(2);
        assertThat(consumerStopInjector.transactionActive).containsOnly(false);
        assertThat(jdbc.queryForMap("SELECT status, CAST(lock_version AS SIGNED) AS version FROM incident"))
                .containsEntry("status", "INVESTIGATING")
                .containsEntry("version", 1L);

        assertThat(assertThrows(() -> faultLab.reset(experimentId), ErrorCode.FAULT_EXPERIMENT_STATE_CONFLICT)
                        .details())
                .containsEntry("currentStatus", "RESET");
        assertThrows(() -> faultLab.reset(999_999), ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(inject("redis-latency", SYSTEM).status()).isEqualTo(FaultExperimentStatus.ACTIVE);
    }

    /**
     * B33-R1 P1：两个注入请求都已读过系统（事务快照已建立）并在系统行锁上排队；先得锁者提交 INJECTING 后，后者的进行中检查须用当前读
     * 看到它而冲突。只有一个 ACTIVE 实验与一个 Incident。
     */
    @Test
    void concurrentInjectionsOnOneSystemAdmitExactlyOne() throws Exception {
        List<Object> outcomes =
                race(() -> inject("redis-latency", SYSTEM), () -> inject("statistics-consumer-stop", SYSTEM));

        assertThat(outcomes).filteredOn(InjectFaultResult.class::isInstance).hasSize(1);
        assertThat(outcomes)
                .filteredOn(ErrorCode.FAULT_EXPERIMENT_STATE_CONFLICT::equals)
                .hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fault_experiment WHERE status = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
        assertThat(incidentCount()).isEqualTo(1);
    }

    /** 同一系统上一个 FAILED 实验的 Reset 与新的注入在系统行锁上竞争：只有一个成功，另一个冲突，不会一边恢复一边注入。 */
    @Test
    void resetAndInjectionOnOneSystemDoNotOverlap() throws Exception {
        redisLatencyInjector.verifyFailure = new FaultInjectionException("Redis latency gate not reached");
        long failed = ((Number) injectFails("redis-latency", SYSTEM, ErrorCode.FAULT_INJECTION_FAILED)
                        .details()
                        .get("experimentId"))
                .longValue();
        redisLatencyInjector.verifyFailure = null;

        List<Object> outcomes = race(() -> faultLab.reset(failed), () -> inject("statistics-consumer-stop", SYSTEM));

        assertThat(outcomes)
                .filteredOn(ErrorCode.FAULT_EXPERIMENT_STATE_CONFLICT::equals)
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> outcome instanceof InjectFaultResult || outcome instanceof ResetFaultResult)
                .hasSize(1);
    }

    /**
     * B34 修复（TASK-092 死锁）：Reset 的收尾事务先取系统行锁。另一事务持有系统行锁并当前读进行中实验（注入/Reset 准入的做法）时，收尾
     * 只在系统行锁上等待、未触及实验行，当前读立即返回；修复前收尾的 UPDATE 先锁住 (system, status) 索引记录、再因外键检查等系统行 S 锁，
     * 与当前读成环被 InnoDB 判为死锁。
     */
    @Test
    void finishingAResetWaitsForTheSystemLockWithoutDeadlock() throws Exception {
        long experimentId = inject("statistics-consumer-stop", SYSTEM).experimentId();
        consumerStopInjector.resetEntered = new CountDownLatch(1);
        consumerStopInjector.releaseReset = new CountDownLatch(1);
        CompletableFuture<Object> reset = outcome(() -> faultLab.reset(experimentId));
        assertThat(consumerStopInjector.resetEntered.await(10, TimeUnit.SECONDS))
                .isTrue();

        try (Connection holder = holderConnection();
                Connection observer = observerConnection()) {
            lockSystemRow(holder);
            consumerStopInjector.releaseReset.countDown();
            awaitStatusWriterWaiting(observer);
            assertThat(countInProgressForShare(holder)).isEqualTo(1);
            holder.commit();
        }
        assertThat(reset.get(10, TimeUnit.SECONDS)).isInstanceOf(ResetFaultResult.class);
        assertThat(experiment(experimentId)).containsEntry("status", "RESET");
    }

    /** 同上，注入未确认时记录 FAILED 的事务也先取系统行锁。 */
    @Test
    void recordingAFailedInjectionWaitsForTheSystemLockWithoutDeadlock() throws Exception {
        try (Connection holder = holderConnection();
                Connection observer = observerConnection()) {
            CountDownLatch holderLocked = new CountDownLatch(1);
            consumerStopInjector.onVerify = () -> {
                lockSystemRow(holder);
                holderLocked.countDown();
            };
            consumerStopInjector.verifyFailure = new FaultInjectionException("Gate failed: not met");
            CompletableFuture<Object> injected = outcome(() -> inject("statistics-consumer-stop", SYSTEM));

            // 准入事务已提交（INJECTING）、确认期间他人取得系统行锁之后，失败记录才开始
            assertThat(holderLocked.await(10, TimeUnit.SECONDS)).isTrue();
            awaitStatusWriterWaiting(observer);
            assertThat(countInProgressForShare(holder)).isEqualTo(1);
            holder.commit();
            assertThat(injected.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.FAULT_INJECTION_FAILED);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM fault_experiment", String.class))
                .isEqualTo("FAILED");
    }

    private static Connection holderConnection() throws Exception {
        Connection holder = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        holder.setAutoCommit(false);
        return holder;
    }

    /** processlist 需要 PROCESS 权限（Testcontainers 的 root 与应用用户同密码）。 */
    private static Connection observerConnection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    private static void lockSystemRow(Connection holder) {
        try (var lock = holder.prepareStatement("SELECT id FROM managed_system WHERE system_key = ? FOR UPDATE")) {
            lock.setString(1, SYSTEM);
            lock.executeQuery().close();
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 与注入/Reset 准入相同的当前读（FOR SHARE）。 */
    private static int countInProgressForShare(Connection holder) throws Exception {
        try (var count = holder.prepareStatement("SELECT COUNT(*) FROM fault_experiment f JOIN managed_system s"
                + " ON s.id = f.managed_system_id WHERE s.system_key = ?"
                + " AND f.status IN ('INJECTING', 'ACTIVE', 'RESETTING') FOR SHARE OF f")) {
            count.setString(1, SYSTEM);
            try (var rs = count.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** 等到状态收尾事务在锁上等待：修复后停在系统行锁，修复前停在 UPDATE fault_experiment。 */
    private static void awaitStatusWriterWaiting(Connection observer) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            try (var statement = observer.createStatement();
                    var rs = statement.executeQuery("SELECT COUNT(*) FROM information_schema.processlist"
                            + " WHERE (info LIKE 'SELECT id FROM managed_system WHERE id = % FOR UPDATE'"
                            + " OR info LIKE '%UPDATE fault_experiment%') AND id <> CONNECTION_ID()")) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }
            assertThat(System.nanoTime())
                    .as("status writer waits for a lock: " + statements(observer))
                    .isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    /**
     * 用独立连接持有系统行锁，直到两个动作都在等这把锁（各自事务已读过系统、快照已建立），再释放。
     *
     * @return 每个动作的结果，或其 ApplicationException 的错误码
     */
    private List<Object> race(Supplier<?> first, Supplier<?> second) throws Exception {
        try (Connection holder =
                        DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                // 查看其他会话的 processlist 需要 PROCESS 权限，只用于观察锁等待（Testcontainers 的 root 与应用用户同密码）
                Connection observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT id FROM managed_system WHERE system_key = ? FOR UPDATE")) {
                lock.setString(1, SYSTEM);
                lock.executeQuery().close();
            }
            List<CompletableFuture<Object>> running = List.of(outcome(first), outcome(second));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (lockWaits(observer) < 2) {
                assertThat(System.nanoTime())
                        .as("both actions wait for the system lock: " + statements(observer))
                        .isLessThan(deadline);
                Thread.sleep(20);
            }
            holder.commit();
            List<Object> outcomes = new ArrayList<>();
            for (CompletableFuture<Object> action : running) {
                outcomes.add(action.get(10, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    /** 正在执行的语句（失败信息）。 */
    private static String statements(Connection observer) throws Exception {
        StringBuilder dump = new StringBuilder();
        try (var statement = observer.createStatement();
                var rs = statement.executeQuery(
                        "SELECT state, info FROM information_schema.processlist WHERE info IS NOT NULL")) {
            while (rs.next()) {
                dump.append(rs.getString(1))
                        .append(" / ")
                        .append(rs.getString(2))
                        .append("; ");
            }
        }
        return dump.toString();
    }

    /**
     * 正在等系统行锁的语句数。主键等值的 FOR UPDATE 在等锁时停在 statistics 阶段，information_schema.innodb_trx 不一定标为 LOCK WAIT，
     * 因此按 processlist 中尚未返回的锁定语句计数（持锁连接的语句已返回）。
     */
    private static int lockWaits(Connection observer) throws Exception {
        try (var statement = observer.createStatement();
                var rs = statement.executeQuery("SELECT COUNT(*) FROM information_schema.processlist"
                        + " WHERE info LIKE 'SELECT id FROM managed_system WHERE id = % FOR UPDATE'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** 每个动作一个独立线程（公共线程池并行度可能为 1，第二个动作会排在被阻塞的第一个之后）。 */
    private static CompletableFuture<Object> outcome(Supplier<?> action) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon().start(() -> {
            try {
                result.complete(action.get());
            } catch (ApplicationException ex) {
                result.complete(ex.errorCode());
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
            }
        });
        return result;
    }

    /** 启动时把旧进程停在注入或重置中的实验记为 FAILED，界限之后的不动；之后同一系统可以重新注入。 */
    @Test
    void startupRecordsInterruptedExperiments() {
        long injecting = insertExperiment("INJECTING", LocalDateTime.of(2026, 1, 1, 0, 0));
        long recent = insertExperiment("RESETTING", LocalDateTime.of(2099, 1, 1, 0, 0));

        assertThat(interruptions.recordInterrupted(Instant.parse("2026-06-01T00:00:00Z")))
                .isEqualTo(1);

        assertThat(experiment(injecting))
                .containsEntry("status", "FAILED")
                .containsEntry("error_message", "Java process exited before the fault lab action finished");
        assertThat(experiment(recent)).containsEntry("status", "RESETTING");
    }

    /** V007 约束（08 TASK-090）：状态伴随字段、Ground Truth schema 与伴随列一致、场景键格式、状态取值；违反即 3819 并指明约束。 */
    @Test
    void schemaRejectsInconsistentExperiments() {
        long active = inject("statistics-consumer-stop", SYSTEM).experimentId();
        assertViolates(
                "UPDATE fault_experiment SET incident_id = NULL WHERE id = " + active, "ck_fault_experiment_state");
        assertViolates(
                "UPDATE fault_experiment SET error_message = 'x' WHERE id = " + active, "ck_fault_experiment_state");
        assertViolates(
                "UPDATE fault_experiment SET status = 'FAILED' WHERE id = " + active, "ck_fault_experiment_state");
        assertViolates(
                "UPDATE fault_experiment SET status = 'RESET' WHERE id = " + active, "ck_fault_experiment_state");
        assertViolates(
                "UPDATE fault_experiment SET status = 'active' WHERE id = " + active,
                "ck_fault_experiment_(status|state)");
        assertViolates(
                "UPDATE fault_experiment SET ground_truth_schema_name = 'other' WHERE id = " + active,
                "ck_fault_experiment_ground_truth");
        assertViolates(
                "UPDATE fault_experiment SET ground_truth_payload = JSON_REMOVE(ground_truth_payload, '$.schemaVersion')"
                        + " WHERE id = " + active,
                "ck_fault_experiment_ground_truth");
        assertViolates(
                "UPDATE fault_experiment SET scenario_key = 'Redis_Latency' WHERE id = " + active,
                "ck_fault_experiment_scenario_key");
        assertViolates(
                "UPDATE fault_experiment SET status = 'RESET', reset_at = injected_at - INTERVAL 1 SECOND WHERE id = "
                        + active,
                "ck_fault_experiment_times");
    }

    /** @param constraint 约束名的正则：非法状态同时违反取值与伴随字段约束，MySQL 只报告其一 */
    private void assertViolates(String sql, String constraint) {
        assertThatThrownBy(() -> jdbc.update(sql))
                .hasMessageContaining("3819")
                .hasMessageMatching("(?s).*Check constraint '" + constraint + "' is violated.*");
    }

    // ---------------------------------------------------------------- 工具

    private InjectFaultResult inject(String scenarioKey, String systemKey) {
        return faultLab.inject(new InjectFaultCommand(scenarioKey, systemKey, "demo-user"));
    }

    private ApplicationException injectFails(String scenarioKey, String systemKey, ErrorCode code) {
        return assertThrows(() -> inject(scenarioKey, systemKey), code);
    }

    private static ApplicationException assertThrows(Runnable action, ErrorCode code) {
        try {
            action.run();
        } catch (ApplicationException ex) {
            assertThat(ex.errorCode()).isEqualTo(code);
            return ex;
        }
        throw new AssertionError("expected " + code);
    }

    private Map<String, Object> experiment(long id) {
        return jdbc.queryForMap(
                "SELECT f.status, f.scenario_key, r.resource_key, f.incident_id, f.injected_at, f.reset_at,"
                        + " f.error_message FROM fault_experiment f JOIN managed_resource r ON r.id = f.target_resource_id"
                        + " WHERE f.id = ?",
                id);
    }

    private int incidentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM incident", Integer.class);
    }

    private long insertExperiment(String status, LocalDateTime updatedAt) {
        jdbc.update(
                "INSERT INTO fault_experiment (scenario_key, managed_system_id, target_resource_id, status,"
                        + " ground_truth_schema_name, ground_truth_schema_version, ground_truth_payload, created_at,"
                        + " updated_at) SELECT 'statistics-consumer-stop', s.id, r.id, ?, 'fault-lab.ground-truth', 1,"
                        + " '{\"schemaName\": \"fault-lab.ground-truth\", \"schemaVersion\": 1,"
                        + " \"cause\": \"STATISTICS_CONSUMER_STOPPED\"}', ?, ? FROM managed_system s JOIN managed_resource r"
                        + " ON r.managed_system_id = s.id WHERE s.system_key = ? AND r.resource_key = 'statistics-consumer'",
                status,
                updatedAt,
                updatedAt,
                status.equals("INJECTING") ? SYSTEM : "test-platform");
        return jdbc.queryForObject("SELECT MAX(id) FROM fault_experiment", Long.class);
    }

    /** 与 demo 系统资源键相同的另一个系统（不同环境）。 */
    private void system(String systemKey, String environment) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM managed_system WHERE system_key = ?", Integer.class, systemKey)
                == 0) {
            jdbc.update(
                    "INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                    systemKey,
                    systemKey,
                    environment);
            jdbc.update(
                    "INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                            + " created_at, updated_at) SELECT n.id, r.resource_key, r.name, r.resource_type, 'ACTIVE',"
                            + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM managed_resource r JOIN managed_system s"
                            + " ON s.id = r.managed_system_id JOIN managed_system n ON n.system_key = ?"
                            + " WHERE s.system_key = ?",
                    systemKey,
                    SYSTEM);
        }
        jdbc.update(
                "UPDATE managed_resource r JOIN managed_system s ON s.id = r.managed_system_id SET r.status = 'ACTIVE'"
                        + " WHERE s.system_key = ?",
                systemKey);
    }

    private static Instant time(Object value) {
        return value instanceof LocalDateTime local ? local.toInstant(ZoneOffset.UTC) : ((Timestamp) value).toInstant();
    }
}
