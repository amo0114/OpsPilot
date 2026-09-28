package io.github.ismoyuan.opspilot.infrastructure.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.investigation.context.InvestigationContextBuilder;
import io.github.ismoyuan.opspilot.application.investigation.context.InvestigationStepContext;
import io.github.ismoyuan.opspilot.infrastructure.ai.AiProtocolCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 真实 MySQL 上验证上下文隔离（08 TASK-037、09 §20～§22）：本轮观测＋以前 Diagnosis 冻结的历史证据及其观测进入；旧 run 未被引用的
 * 迟到结果、旧 run 未冻结的证据、他调查数据、非调查类时间线不进入；完整序列化请求中没有 Ground Truth、Fault Lab 控制信息、
 * 凭证或原始 payload，且是合法的 v1 请求。
 */
@SpringBootTest
@Testcontainers
@Import({InvestigationContextBuilder.class, InvestigationContextIntegrationTest.FixedClock.class})
class InvestigationContextIntegrationTest {

    static final Instant NOW = Instant.parse("2026-09-28T08:00:00.000Z");
    static final Instant RUN_2_STARTED = NOW.minusSeconds(100);
    static final Instant RUN_1_TIME = NOW.minusSeconds(3_600);

    /** 这些标记放在不应被读取的位置；序列化请求中出现任何一个即泄漏。 */
    static final List<String> FORBIDDEN = List.of(
            "hunter2",
            "REDIS_NETWORK_LATENCY",
            "groundTruth",
            "scenarioKey",
            "fault injected",
            "env://OPSPILOT_SECRET_MARKER",
            "rawPayloadMarker",
            "late-result-marker",
            "other-incident-marker",
            "old-unfrozen-marker");

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
    InvestigationContextBuilder builder;

    @Autowired
    JdbcTemplate jdbc;

    InvestigationFixture fixture;
    long consumer;
    long h1;
    long h2;
    long o1Frozen;
    long o2Late;
    long o3Current;
    long oOld;
    long e1Frozen;
    long e3Current;
    long diagnosedIncident;

    @BeforeEach
    void seed() {
        fixture = InvestigationFixture.reset(jdbc);
        jdbc.update("DELETE FROM data_source_connection");
        long incident = fixture.incidentId();
        long investigation = fixture.investigationId();
        jdbc.update("INSERT INTO data_source_connection (connection_key, name, provider_type, endpoint, credential_ref,"
                + " config_schema_name, config_schema_version, config_payload, status, created_at, updated_at)"
                + " VALUES ('redis-main', 'Redis', 'REDIS', 'redis://redis:6379',"
                + " 'env://OPSPILOT_SECRET_MARKER', 'redis.connection.config', 1, '{}', 'ACTIVE',"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO managed_resource (managed_system_id, resource_key, name, resource_type, status,"
                + " created_at, updated_at) SELECT id, 'statistics-consumer', 'Consumer', 'CONSUMER', 'ACTIVE',"
                + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM managed_system");
        consumer = jdbc.queryForObject(
                "SELECT id FROM managed_resource WHERE resource_key = 'statistics-consumer'", Long.class);
        jdbc.update(
                "INSERT INTO incident_affected_resource (incident_id, managed_resource_id, created_at)"
                        + " VALUES (?, ?, UTC_TIMESTAMP(3))",
                incident,
                consumer);
        jdbc.update(
                "UPDATE incident SET title = '统计消息持续积压', impact_summary = '统计数据无法及时更新',"
                        + " description = 'groundTruth: REDIS_NETWORK_LATENCY scenarioKey=S1', started_at = ?"
                        + " WHERE id = ?",
                utc(RUN_1_TIME),
                incident);
        jdbc.update(
                "UPDATE investigation SET current_run_no = 2, current_run_started_at = ?,"
                        + " current_run_capability_count = 3, max_capability_calls = 12, max_duration_seconds = 480"
                        + " WHERE id = ?",
                utc(RUN_2_STARTED),
                investigation);

        h1 = hypothesis(investigation, "统计消费者已停止", "SUPPORTED");
        h2 = hypothesis(investigation, "Producer 已停止生产", "REFUTED");
        hypothesis(fixture.otherInvestigationId(), "other-incident-marker", "PENDING");

        // run 1：被 v1 冻结引用的观测、未被引用的旧观测，以及 run 2 开始后才返回的 run 1 迟到结果
        o1Frozen = observation(incident, investigation, 1, "run1-frozen", "消费者组 lag=2180", RUN_1_TIME);
        oOld = observation(incident, investigation, 1, "run1-old", "old-unfrozen-marker", RUN_1_TIME);
        o2Late = observation(incident, investigation, 1, "run1-late", "late-result-marker", NOW.minusSeconds(30));
        o3Current = observation(incident, investigation, 2, "run2-current", "消费者容器已退出", NOW.minusSeconds(60));
        observation(
                fixture.otherIncidentId(), fixture.otherInvestigationId(), 1, "other", "other-incident-marker", NOW);

        e1Frozen = evidence(investigation, o1Frozen, h1, "SUPPORTS", "积压持续增长", RUN_1_TIME);
        evidence(investigation, oOld, h2, "CONTEXT", "old-unfrozen-marker", RUN_1_TIME);
        e3Current = evidence(investigation, o3Current, h1, "SUPPORTS", "容器已退出", NOW.minusSeconds(50));
        jdbc.update(
                "INSERT INTO diagnosis (investigation_id, run_no, version_no, conclusion_type, primary_hypothesis_id,"
                        + " summary, impact_summary, termination_reason, created_at) VALUES (?, 1, 1,"
                        + " 'POSSIBLE_CAUSE', ?, '可能是统计消费者停止。', '统计延迟', 'AGENT_COMPLETED', ?)",
                investigation,
                h1,
                utc(RUN_1_TIME));
        long diagnosis =
                jdbc.queryForObject("SELECT id FROM diagnosis WHERE investigation_id = ?", Long.class, investigation);
        jdbc.update(
                "INSERT INTO diagnosis_evidence_ref (diagnosis_id, evidence_id, created_at) VALUES (?, ?, ?)",
                diagnosis,
                e1Frozen,
                utc(RUN_1_TIME));

        for (int i = 1; i <= 25; i++) {
            timeline(incident, "HYPOTHESIS_CREATED", "提出待验证原因 " + i, NOW.minusSeconds(90 - i));
        }
        timeline(incident, "FAULT_INJECTED", "fault injected REDIS_NETWORK_LATENCY", NOW.minusSeconds(10));

        diagnosedIncident = fixture.otherIncidentId();
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED' WHERE id = ?", diagnosedIncident);
    }

    @Test
    void contextHoldsCurrentRunFactsAndFrozenHistoryOnly() {
        InvestigationStepContext context =
                builder.build(fixture.incidentId(), 2).orElseThrow();

        assertThat(context.runNo()).isEqualTo(2);
        assertThat(context.incident().title()).isEqualTo("统计消息持续积压");
        assertThat(context.affectedResources())
                .extracting(InvestigationStepRequest.AffectedResource::resourceKey)
                .containsExactly("statistics-consumer");
        assertThat(context.hypotheses())
                .extracting(InvestigationStepRequest.Hypothesis::id)
                .containsExactly(h1, h2);
        assertThat(context.evidence())
                .extracting(InvestigationStepRequest.Evidence::id)
                .containsExactly(e1Frozen, e3Current);
        assertThat(context.observations())
                .extracting(InvestigationStepRequest.Observation::id, InvestigationStepRequest.Observation::runNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(o1Frozen, 1),
                        org.assertj.core.groups.Tuple.tuple(o3Current, 2));
        assertThat(context.currentDiagnosis().version()).isOne();
        assertThat(context.currentDiagnosis().evidenceIds()).containsExactly(e1Frozen);
        assertThat(context.budget())
                .isEqualTo(new InvestigationStepRequest.Budget(
                        InvestigationStepRequest.BudgetScope.ACTIVE_RUN, 3, 12, 9, 100, 480));
        // 允许类型中最近 20 条，按时间先后；FAULT_INJECTED 不进入
        assertThat(context.recentTimeline()).hasSize(InvestigationContextBuilder.RECENT_TIMELINE_LIMIT);
        assertThat(context.recentTimeline().getFirst().summary()).isEqualTo("提出待验证原因 6");
        assertThat(context.recentTimeline().getLast().summary()).isEqualTo("提出待验证原因 25");
        assertThat(context.availableCapabilities()).isEmpty();
    }

    /** 完整序列化请求（而非某个模板）不含 Ground Truth、控制信息、凭证、原始 payload 或旧轮迟到结果，并是合法的 v1 请求。 */
    @Test
    void serializedRequestLeaksNothingAndIsProtocolValid() {
        AiProtocolCodec codec = new AiProtocolCodec();
        InvestigationStepRequest request =
                builder.build(fixture.incidentId(), 2).orElseThrow().toRequest(99, "corr_context_test");

        String json = codec.encode(request);

        for (String marker : FORBIDDEN) {
            assertThat(json).as(marker).doesNotContain(marker);
        }
        assertThat(codec.decode(json, InvestigationStepRequest.class)).isEqualTo(request);
    }

    @Test
    void staleRunOrInactiveIncidentYieldsNoContext() {
        assertThat(builder.build(fixture.incidentId(), 1)).isEmpty();
        assertThat(builder.build(fixture.incidentId(), 3)).isEmpty();
        assertThat(builder.build(diagnosedIncident, 1)).isEmpty();
        assertThat(builder.build(999_999L, 1)).isEmpty();
    }

    private long hypothesis(long investigation, String title, String status) {
        jdbc.update(
                "INSERT INTO hypothesis (investigation_id, title, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                investigation,
                title,
                status);
        return jdbc.queryForObject("SELECT MAX(id) FROM hypothesis", Long.class);
    }

    /** 来源调用的请求/响应与观测 payload 都放入禁止标记，验证上下文不读取它们。 */
    private long observation(long incident, long investigation, int runNo, String name, String summary, Instant at) {
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, investigation_id, run_no, capability_key,"
                        + " managed_resource_id, status, request_schema_name, request_schema_version, request_payload,"
                        + " response_schema_name, response_schema_version, response_payload, started_at, finished_at,"
                        + " duration_ms, correlation_id, created_at, updated_at) VALUES (?, ?, ?, 'queue.inspect', ?,"
                        + " 'SUCCEEDED', 'queue.inspect.request', 1, '{\"password\": \"hunter2\"}',"
                        + " 'queue.inspect.result', 1, '{\"groundTruth\": \"REDIS_NETWORK_LATENCY\"}', ?, ?, 5, ?, ?, ?)",
                incident,
                investigation,
                runNo,
                fixture.streamId(),
                utc(at),
                utc(at),
                name,
                utc(at),
                utc(at));
        long invocation =
                jdbc.queryForObject("SELECT id FROM capability_invocation WHERE correlation_id = ?", Long.class, name);
        jdbc.update(
                "INSERT INTO observation (incident_id, investigation_id, capability_invocation_id, managed_resource_id,"
                        + " observation_kind, schema_name, schema_version, payload, summary, observed_at, created_at)"
                        + " VALUES (?, ?, ?, ?, 'QUEUE_STATUS', 'queue.inspect.result', 1,"
                        + " '{\"rawPayloadMarker\": \"hunter2\"}', ?, ?, ?)",
                incident,
                investigation,
                invocation,
                fixture.streamId(),
                summary,
                utc(at),
                utc(at));
        return jdbc.queryForObject(
                "SELECT id FROM observation WHERE capability_invocation_id = ?", Long.class, invocation);
    }

    private long evidence(
            long investigation, long observation, long hypothesis, String relation, String reason, Instant at) {
        jdbc.update(
                "INSERT INTO evidence (investigation_id, observation_id, hypothesis_id, relation, reason, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                investigation,
                observation,
                hypothesis,
                relation,
                reason,
                utc(at));
        return jdbc.queryForObject("SELECT MAX(id) FROM evidence", Long.class);
    }

    private void timeline(long incident, String type, String summary, Instant at) {
        jdbc.update(
                "INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary, payload,"
                        + " created_at) VALUES (?, ?, ?, 'SYSTEM', ?, JSON_OBJECT('schemaName', 'test.event',"
                        + " 'schemaVersion', 1, 'groundTruth', 'REDIS_NETWORK_LATENCY'), ?)",
                incident,
                type,
                utc(at),
                summary,
                utc(at));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
