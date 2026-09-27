package io.github.ismoyuan.opspilot.infrastructure.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.incident.CreateIncidentCommand;
import io.github.ismoyuan.opspilot.application.incident.CreateIncidentResult;
import io.github.ismoyuan.opspilot.application.incident.IncidentApplicationService;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentSource;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证创建 Incident（05 §20～§21、08 TASK-015）：Incident＋受影响资源＋INCIDENT_CREATED 时间线
 * 同事务提交或回滚，归属与键精确匹配，编号按 UTC 日期递增且并发不重复。每个用例清空故障表后自行准备数据。
 */
@SpringBootTest
@Testcontainers
@Import({IncidentApplicationService.class, CreateIncidentIntegrationTest.FixedClock.class})
class CreateIncidentIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-27T15:30:00.123456Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 可调的 UTC 时钟。 */
    @TestConfiguration
    static class FixedClock {

        static final AtomicReference<Instant> INSTANT = new AtomicReference<>(NOW);

        @Bean
        Clock clock() {
            return new Clock() {
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
                    return INSTANT.get();
                }
            };
        }
    }

    @Autowired
    IncidentApplicationService service;

    @Autowired
    IncidentRepository incidents;

    @MockitoSpyBean
    TimelineRepository timeline;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        FixedClock.INSTANT.set(NOW);
        jdbc.update("DELETE FROM incident_timeline_event");
        jdbc.update("DELETE FROM incident_affected_resource");
        jdbc.update("DELETE FROM incident");
        jdbc.update("DELETE FROM managed_resource");
        jdbc.update("DELETE FROM managed_system");
        jdbc.update("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at) VALUES"
                + " ('shortlink-platform', 'ShortLink', 'DEMO', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),"
                + " ('billing-platform', 'Billing', 'DEMO', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),"
                + " ('legacy-platform', 'Legacy', 'DEMO', 'DISABLED', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        insertResource("shortlink-platform", "redirect-service", "SERVICE");
        insertResource("shortlink-platform", "shortlink-redis", "CACHE");
        insertResource("billing-platform", "billing-service", "SERVICE");
    }

    @Test
    void createsIncidentWithAffectedResourcesAndTimelineInOneCommit() {
        CreateIncidentResult result;
        try (Correlation.Scope ignored = Correlation.open("req_create-1")) {
            result = service.createIncident(command(List.of("shortlink-redis", "redirect-service")));
        }

        assertThat(result)
                .isEqualTo(new CreateIncidentResult(new IncidentKey("INC-20260927-0001"), IncidentStatus.CREATED, 0));
        Incident incident = incidents.findByKey(result.incidentKey()).orElseThrow();
        assertThat(incident.title()).isEqualTo("短链接跳转明显变慢");
        assertThat(incident.description()).isNull();
        assertThat(incident.createdSource()).isEqualTo(IncidentSource.MANUAL);
        assertThat(incident.createdBy()).isEqualTo("demo-user");
        assertThat(incident.detectedAt()).isEqualTo(Instant.parse("2026-09-27T15:30:00.123Z"));
        assertThat(incident.startedAt()).isEqualTo(incident.detectedAt());
        assertThat(jdbc.queryForList(
                        "SELECT r.resource_key FROM incident_affected_resource a JOIN managed_resource r"
                                + " ON r.id = a.managed_resource_id WHERE a.incident_id = ? ORDER BY r.resource_key",
                        String.class,
                        incident.id()))
                .containsExactly("redirect-service", "shortlink-redis");

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT event_type, actor_type, actor_id, summary, correlation_id,"
                        + " DATE_FORMAT(occurred_at, '%Y-%m-%dT%H:%i:%s.%f') AS occurred,"
                        + " payload->>'$.schemaName' AS schema_name, payload->>'$.schemaVersion' AS schema_version,"
                        + " payload->>'$.incidentKey' AS incident_key, payload->>'$.systemKey' AS system_key,"
                        + " payload->>'$.createdSource' AS created_source,"
                        + " JSON_LENGTH(payload->'$.affectedResourceKeys') AS affected"
                        + " FROM incident_timeline_event WHERE incident_id = ?",
                incident.id());
        assertThat(event)
                .containsEntry("event_type", "INCIDENT_CREATED")
                .containsEntry("actor_type", "USER")
                .containsEntry("actor_id", "demo-user")
                .containsEntry("summary", "创建故障：短链接跳转明显变慢")
                .containsEntry("correlation_id", "req_create-1")
                .containsEntry("occurred", "2026-09-27T15:30:00.123000")
                .containsEntry("schema_name", "timeline.incident-created")
                .containsEntry("schema_version", "1")
                .containsEntry("incident_key", "INC-20260927-0001")
                .containsEntry("system_key", "shortlink-platform")
                .containsEntry("created_source", "MANUAL");
        assertThat(((Number) event.get("affected")).intValue()).isEqualTo(2);
    }

    @Test
    void keysIncreaseWithinTheUtcDayAndRestartNextDay() {
        assertThat(service.createIncident(command(List.of())).incidentKey().value())
                .isEqualTo("INC-20260927-0001");
        assertThat(service.createIncident(command(null)).incidentKey().value()).isEqualTo("INC-20260927-0002");

        FixedClock.INSTANT.set(Instant.parse("2026-09-28T00:00:00.001Z"));
        assertThat(service.createIncident(command(List.of())).incidentKey().value())
                .isEqualTo("INC-20260928-0001");

        jdbc.update("UPDATE incident SET incident_key = 'INC-20260928-10000' WHERE incident_key = 'INC-20260928-0001'");
        assertThat(service.createIncident(command(List.of())).incidentKey().value())
                .isEqualTo("INC-20260928-10001");
    }

    @Test
    void faultLabSourceIsRecordedAsSystemActor() {
        CreateIncidentResult result = service.createIncident(new CreateIncidentCommand(
                "shortlink-platform",
                "注入：Redis 延迟",
                null,
                "跳转变慢",
                null,
                List.of("shortlink-redis"),
                IncidentSource.FAULT_LAB,
                "demo-user"));

        assertThat(incidents.findByKey(result.incidentKey()).orElseThrow().createdSource())
                .isEqualTo(IncidentSource.FAULT_LAB);
        assertThat(jdbc.queryForObject("SELECT actor_type FROM incident_timeline_event", String.class))
                .isEqualTo("SYSTEM");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-platform", "SHORTLINK-PLATFORM", "shortlink-platform ", "shortlink-plátform"})
    void unknownOrVariantSystemKeyIsSystemNotFound(String systemKey) {
        assertRejected(
                () -> service.createIncident(new CreateIncidentCommand(
                        systemKey, "标题", null, "影响", null, List.of(), IncidentSource.MANUAL, "demo-user")),
                ErrorCode.SYSTEM_NOT_FOUND);
    }

    @Test
    void inactiveSystemIsRejected() {
        assertRejected(
                () -> service.createIncident(new CreateIncidentCommand(
                        "legacy-platform", "标题", null, "影响", null, List.of(), IncidentSource.MANUAL, "demo-user")),
                ErrorCode.REQUEST_VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"billing-service", "missing-service", "Redirect-Service", "redirect-service "})
    void resourceOutsideTheSystemIsRejected(String resourceKey) {
        OpsPilotException ex = assertRejected(
                () -> service.createIncident(command(List.of("redirect-service", resourceKey))),
                ErrorCode.RESOURCE_NOT_IN_SYSTEM);
        assertThat(ex.details())
                .containsEntry("systemKey", "shortlink-platform")
                .containsEntry("resourceKeys", List.of(resourceKey));
    }

    @Test
    void duplicateOrBlankResourceKeysAreRejected() {
        assertRejected(
                () -> service.createIncident(command(List.of("redirect-service", "redirect-service"))),
                ErrorCode.REQUEST_VALIDATION_FAILED);
        assertRejected(() -> service.createIncident(command(List.of(" "))), ErrorCode.REQUEST_VALIDATION_FAILED);
    }

    @Test
    void invalidTextIsRejectedBeforeAnyWrite() {
        OpsPilotException ex = assertRejected(
                () -> service.createIncident(new CreateIncidentCommand(
                        "shortlink-platform",
                        "t".repeat(201),
                        null,
                        "影响",
                        null,
                        List.of(),
                        IncidentSource.MANUAL,
                        "demo-user")),
                ErrorCode.REQUEST_VALIDATION_FAILED);
        assertThat(ex).isInstanceOf(DomainException.class);
        assertThat(ex.details()).containsEntry("field", "title");
    }

    /** 时间线写入失败时，已插入的 Incident 与受影响资源随同一事务回滚。 */
    @Test
    void timelineFailureRollsBackIncidentAndAffectedResources() {
        doThrow(new IllegalStateException("timeline down")).when(timeline).append(any());

        assertThatThrownBy(() -> service.createIncident(command(List.of("redirect-service"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("incident")).isZero();
        assertThat(count("incident_affected_resource")).isZero();
        assertThat(count("incident_timeline_event")).isZero();
    }

    /** 同时创建的请求都成功，得到当日连续且不重复的编号，且每个 Incident 恰有一条创建事件。 */
    @Test
    void concurrentCreationsGetDistinctConsecutiveKeys() throws Exception {
        int creators = 4;
        ExecutorService executor = Executors.newFixedThreadPool(creators);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<CreateIncidentResult>> futures = new ArrayList<>();
            for (int i = 0; i < creators; i++) {
                futures.add(executor.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return service.createIncident(command(List.of("redirect-service")));
                }));
            }
            start.countDown();
            List<String> keys = new ArrayList<>();
            for (Future<CreateIncidentResult> future : futures) {
                keys.add(future.get(60, TimeUnit.SECONDS).incidentKey().value());
            }

            assertThat(keys)
                    .containsExactlyInAnyOrder(
                            "INC-20260927-0001", "INC-20260927-0002", "INC-20260927-0003", "INC-20260927-0004");
            assertThat(count("incident_timeline_event")).isEqualTo(creators);
            assertThat(count("incident_affected_resource")).isEqualTo(creators);
        } finally {
            executor.shutdownNow();
        }
    }

    private static CreateIncidentCommand command(List<String> affectedResourceKeys) {
        return new CreateIncidentCommand(
                "shortlink-platform",
                "短链接跳转明显变慢",
                null,
                "短链接跳转速度明显下降",
                null,
                affectedResourceKeys,
                IncidentSource.MANUAL,
                "demo-user");
    }

    private OpsPilotException assertRejected(Runnable action, ErrorCode code) {
        OpsPilotException[] caught = new OpsPilotException[1];
        assertThatThrownBy(action::run).isInstanceOfSatisfying(OpsPilotException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(code);
            caught[0] = ex;
        });
        assertThat(count("incident")).isZero();
        assertThat(count("incident_timeline_event")).isZero();
        return caught[0];
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void insertResource(String systemKey, String resourceKey, String type) {
        jdbc.update(
                "INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                        + " created_at, updated_at) SELECT id, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)"
                        + " FROM managed_system WHERE system_key = ?",
                resourceKey,
                resourceKey,
                type,
                systemKey);
    }
}
