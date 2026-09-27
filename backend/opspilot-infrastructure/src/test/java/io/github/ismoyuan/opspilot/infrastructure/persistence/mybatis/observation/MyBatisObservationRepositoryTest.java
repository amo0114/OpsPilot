package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.observation.ObservationRepository;
import io.github.ismoyuan.opspilot.domain.observation.NewObservation;
import io.github.ismoyuan.opspilot.domain.observation.Observation;
import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证 Observation 只插入（08 TASK-022、04 §22～§26）：往返映射，来源 Invocation 必须成功且 Incident、
 * 上下文、资源一致，不一致时不写入；持久层不存在针对 observation 的更新或删除语句。
 */
@SpringBootTest
@Testcontainers
@Transactional
class MyBatisObservationRepositoryTest {

    private static final Instant OBSERVED = Instant.parse("2026-09-27T09:10:11.123Z");
    private static final Instant CREATED = Instant.parse("2026-09-27T09:10:12.456Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    ObservationRepository observations;

    @Autowired
    JdbcTemplate jdbc;

    Map<String, Long> ids;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('shortlink-platform', 'S', 'DEMO', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) SELECT id, key_name, key_name, 'MESSAGE_QUEUE', 'ACTIVE',"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM managed_system,"
                + " (SELECT 'statistics-stream' AS key_name UNION ALL SELECT 'shortlink-redis') k");
        jdbc.update("INSERT INTO incident (incident_key, managed_system_id, title, impact_summary, status,"
                + " created_source, created_by, started_at, detected_at, created_at, updated_at) SELECT"
                + " CONCAT('INC-20260927-000', n), id, 'T', 'I', 'INVESTIGATING', 'MANUAL', 'demo-user',"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM managed_system,"
                + " (SELECT 1 AS n UNION ALL SELECT 2) k");
        jdbc.update("INSERT INTO investigation (incident_id, started_at, last_activity_at, current_run_no,"
                + " current_run_started_at, max_capability_calls, max_duration_seconds, agent_step_timeout_seconds,"
                + " max_consecutive_ai_failures, created_at, updated_at) SELECT id, UTC_TIMESTAMP(3),"
                + " UTC_TIMESTAMP(3), 1, UTC_TIMESTAMP(3), 12, 480, 60, 3, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)"
                + " FROM incident");
        ids = Map.of(
                "incident", id("SELECT id FROM incident WHERE incident_key = 'INC-20260927-0001'"),
                "otherIncident", id("SELECT id FROM incident WHERE incident_key = 'INC-20260927-0002'"),
                "investigation",
                        id("SELECT v.id FROM investigation v JOIN incident i ON i.id = v.incident_id"
                                + " WHERE i.incident_key = 'INC-20260927-0001'"),
                "stream", id("SELECT id FROM managed_resource WHERE resource_key = 'statistics-stream'"),
                "redis", id("SELECT id FROM managed_resource WHERE resource_key = 'shortlink-redis'"));
        insertInvocation("succeeded", "SUCCEEDED", false);
        insertInvocation("running", "RUNNING", false);
        insertInvocation("failed", "FAILED", false);
        insertInvocation("recovery", "SUCCEEDED", true);
    }

    @Test
    void investigationObservationRoundTripsAndListsInOrder() {
        NewObservation first = investigationObservation("succeeded", "{\"lag\": 2180, \"pending\": 3}", OBSERVED);
        Observation stored = observations.insert(first, CREATED);

        assertThat(stored.content()).isEqualTo(first);
        assertThat(stored.createdAt()).isEqualTo(CREATED);
        assertThat(stored.isInvestigationObservation()).isTrue();
        assertThat(observations.findById(stored.id())).contains(stored);

        Observation second = observations.insert(
                investigationObservation("succeeded", "{\"lag\": 2300}", OBSERVED.plusSeconds(10)), CREATED);
        assertThat(observations.findByInvestigationId(ids.get("investigation")))
                .extracting(Observation::id)
                .containsExactly(stored.id(), second.id());
    }

    @Test
    void recoveryObservationKeepsItsOwnContext() {
        NewObservation recovery = new NewObservation(
                ids.get("incident"),
                null,
                9001L,
                invocation("recovery"),
                ids.get("stream"),
                ObservationKind.QUEUE_STATUS,
                "queue.inspect.result",
                1,
                "{\"lag\": 3}",
                "积压降至 3",
                OBSERVED,
                null,
                null);

        Observation stored = observations.insert(recovery, CREATED);

        assertThat(stored.content()).isEqualTo(recovery);
        assertThat(stored.isInvestigationObservation()).isFalse();
        assertThat(observations.findByInvestigationId(ids.get("investigation"))).isEmpty();
    }

    /** 查询未成功不产生 Observation（01 §16）；与来源 Invocation 不一致的 Incident/上下文/资源不写入（04 §22）。 */
    @Test
    void rejectsObservationsThatDoNotMatchASucceededSourceInvocation() {
        NewObservation valid = investigationObservation("succeeded", "{\"lag\": 1}", OBSERVED);
        NewObservation[] invalid = {
            investigationObservation("running", "{\"lag\": 1}", OBSERVED),
            investigationObservation("failed", "{\"lag\": 1}", OBSERVED),
            // 调查上下文却引用恢复调用
            investigationObservation("recovery", "{\"lag\": 1}", OBSERVED),
            withResource(valid, ids.get("redis")),
            withIncident(valid, ids.get("otherIncident"))
        };

        for (NewObservation observation : invalid) {
            assertThatThrownBy(() -> observations.insert(observation, CREATED))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observation", Integer.class))
                .isZero();
    }

    /** 端口只有插入与读取；所有 Mapper XML 中都不存在 UPDATE/DELETE observation 语句（04 §26、INV-008）。 */
    @Test
    void persistenceOffersNoWayToModifyObservations() throws IOException {
        assertThat(Arrays.stream(ObservationRepository.class.getDeclaredMethods())
                        .map(Method::getName))
                .containsExactlyInAnyOrder("insert", "findById", "findByInvestigationId");

        Pattern modify = Pattern.compile("(?is)\\b(update\\s+observation\\b|delete\\s+from\\s+observation\\b)");
        Resource[] mappers =
                new PathMatchingResourcePatternResolver().getResources("classpath*:io/github/**/*Mapper.xml");
        assertThat(mappers).isNotEmpty();
        for (Resource mapper : mappers) {
            String xml = mapper.getContentAsString(StandardCharsets.UTF_8);
            assertThat(modify.matcher(xml).find()).as(mapper.getFilename()).isFalse();
        }
    }

    private NewObservation investigationObservation(String invocation, String payload, Instant observedAt) {
        return new NewObservation(
                ids.get("incident"),
                ids.get("investigation"),
                null,
                invocation(invocation),
                ids.get("stream"),
                ObservationKind.QUEUE_STATUS,
                "queue.inspect.result",
                1,
                payload,
                "消息积压持续增长",
                observedAt,
                observedAt.minusSeconds(900),
                observedAt);
    }

    private static NewObservation withResource(NewObservation o, long resourceId) {
        return new NewObservation(
                o.incidentId(),
                o.investigationId(),
                null,
                o.capabilityInvocationId(),
                resourceId,
                o.kind(),
                o.schemaName(),
                o.schemaVersion(),
                o.payload(),
                o.summary(),
                o.observedAt(),
                o.windowStart(),
                o.windowEnd());
    }

    private static NewObservation withIncident(NewObservation o, long incidentId) {
        return new NewObservation(
                incidentId,
                o.investigationId(),
                null,
                o.capabilityInvocationId(),
                o.managedResourceId(),
                o.kind(),
                o.schemaName(),
                o.schemaVersion(),
                o.payload(),
                o.summary(),
                o.observedAt(),
                o.windowStart(),
                o.windowEnd());
    }

    private long invocation(String name) {
        return id("SELECT id FROM capability_invocation WHERE correlation_id = '" + name + "'");
    }

    private void insertInvocation(String name, String status, boolean recovery) {
        boolean finished = !status.equals("RUNNING");
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, investigation_id, recovery_verification_id, run_no,"
                        + " criterion_key, sample_index, capability_key, managed_resource_id, status,"
                        + " request_schema_name, request_schema_version, request_payload, response_schema_name,"
                        + " response_schema_version, response_payload, started_at, finished_at, duration_ms,"
                        + " error_code, correlation_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?,"
                        + " 'queue.inspect', ?, ?, 'queue.inspect.request', 1, '{}', ?, ?, ?, UTC_TIMESTAMP(3), ?, ?,"
                        + " ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                ids.get("incident"),
                recovery ? null : ids.get("investigation"),
                recovery ? 9001L : null,
                recovery ? null : 1,
                recovery ? "stream-lag-drained" : null,
                recovery ? 1 : null,
                ids.get("stream"),
                status,
                status.equals("SUCCEEDED") ? "queue.inspect.result" : null,
                status.equals("SUCCEEDED") ? 1 : null,
                status.equals("SUCCEEDED") ? "{}" : null,
                finished ? java.sql.Timestamp.from(OBSERVED) : null,
                finished ? 42 : null,
                status.equals("FAILED") ? "PROVIDER_TIMEOUT" : null,
                name);
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
