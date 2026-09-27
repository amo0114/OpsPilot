package io.github.ismoyuan.opspilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.diagnosis.CreateDiagnosisCommand;
import io.github.ismoyuan.opspilot.application.diagnosis.DiagnosisApplicationService;
import io.github.ismoyuan.opspilot.application.evidence.EvidenceApplicationService;
import io.github.ismoyuan.opspilot.application.evidence.LinkEvidenceCommand;
import io.github.ismoyuan.opspilot.application.hypothesis.HypothesisApplicationService;
import io.github.ismoyuan.opspilot.application.hypothesis.ProposeHypothesisCommand;
import io.github.ismoyuan.opspilot.domain.diagnosis.Diagnosis;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisDraft;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import io.github.ismoyuan.opspilot.domain.hypothesis.HypothesisStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实 HTTP＋真实 MySQL（demo Seed）上的调查技术详情 API（05 §49～§56、08 TASK-027）：只读投影、只含本调查的事实
 * （恢复观测不出现）、过滤与分页、Observation 详情原样输出结构化载荷、Diagnosis 详情返回创建时冻结的 Evidence
 * （后来新增 Evidence 不改变旧版本）、未开始调查与不存在时的错误码，且没有写入口。
 * 领域事实经真实用例服务写入（Start/Continue 经 HTTP），Invocation/Observation 由 SQL 预置（其写入口属 TASK-048/051）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class InvestigationApiContractTest {

    private static final String ISO_MILLIS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HypothesisApplicationService hypotheses;

    @Autowired
    EvidenceApplicationService evidence;

    @Autowired
    DiagnosisApplicationService diagnoses;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearIncidents() {
        for (String table : List.of(
                "diagnosis_evidence_ref",
                "diagnosis",
                "evidence",
                "hypothesis",
                "observation",
                "capability_invocation",
                "incident_timeline_event",
                "investigation",
                "incident_affected_resource",
                "incident")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void beforeInvestigationAndForUnknownIncidentsTheQueriesAnswerWithoutFacts() throws Exception {
        String key = createIncident();

        assertError(get("/api/v1/incidents/" + key + "/investigation"), 404, "RESOURCE_NOT_FOUND");
        assertThat(get("/api/v1/incidents/" + key + "/investigation/hypotheses")
                        .body()
                        .path("data")
                        .isEmpty())
                .isTrue();
        Response observations = get("/api/v1/incidents/" + key + "/investigation/observations");
        assertThat(observations.status()).isEqualTo(200);
        assertThat(observations.body().path("page").path("totalElements").asLong())
                .isZero();
        assertThat(get("/api/v1/incidents/" + key + "/investigation/evidence")
                        .body()
                        .path("data")
                        .isEmpty())
                .isTrue();
        assertThat(get("/api/v1/incidents/" + key + "/diagnoses")
                        .body()
                        .path("data")
                        .isEmpty())
                .isTrue();
        assertError(get("/api/v1/incidents/" + key + "/diagnoses/1"), 404, "DIAGNOSIS_NOT_FOUND");
        assertError(get("/api/v1/incidents/INC-20260101-9999/investigation"), 404, "INCIDENT_NOT_FOUND");
        assertError(get("/api/v1/incidents/" + key.toLowerCase() + "/diagnoses"), 404, "INCIDENT_NOT_FOUND");
    }

    @Test
    void technicalDetailsProjectCommittedInvestigationFacts() throws Exception {
        String key = createIncident();
        assertThat(action(key, "start-investigation", 0).status()).isEqualTo(202);
        long incidentId = jdbc.queryForObject("SELECT id FROM incident WHERE incident_key = ?", Long.class, key);
        long investigationId =
                jdbc.queryForObject("SELECT id FROM investigation WHERE incident_id = ?", Long.class, incidentId);

        long lag = observation(incidentId, investigationId, "statistics-stream", "QUEUE_STATUS", "积压 2180", true);
        long consumer =
                observation(incidentId, investigationId, "statistics-consumer", "SERVICE_STATUS", "消费者已停止", false);
        long recovery = observation(incidentId, null, "statistics-stream", "QUEUE_STATUS", "恢复后积压 0", true);
        long h1 = propose(incidentId, "Statistics Consumer 已停止");
        long h2 = propose(incidentId, "Producer 已停止生产消息");
        long e1 = link(incidentId, lag, h1, EvidenceRelation.SUPPORTS, HypothesisStatus.SUPPORTED);
        long e2 = link(incidentId, consumer, h2, EvidenceRelation.REFUTES, HypothesisStatus.REFUTED);
        diagnose(
                incidentId,
                1,
                DiagnosisConclusionType.PRIMARY_CAUSE_IDENTIFIED,
                h1,
                List.of(e1),
                TerminationReason.AGENT_COMPLETED);

        // Continue 进入 run 2 后新增的 Evidence 不改变 v1 的冻结依据
        long version = get("/api/v1/incidents/" + key)
                .body()
                .path("data")
                .path("version")
                .asLong();
        assertThat(action(key, "continue-investigation", version).status()).isEqualTo(202);
        long e3 = link(incidentId, consumer, h1, EvidenceRelation.CONTEXT, null);
        Diagnosis v2 = diagnose(
                incidentId,
                2,
                DiagnosisConclusionType.UNDETERMINED,
                null,
                List.of(e3, e2),
                TerminationReason.CAPABILITY_BUDGET_EXHAUSTED);
        jdbc.update(
                "UPDATE investigation SET current_run_started_at = ? WHERE id = ?",
                utc(v2.createdAt().minusSeconds(125)),
                investigationId);
        String base = "/api/v1/incidents/" + key;

        JsonNode overview = get(base + "/investigation").body().path("data");
        assertThat(overview.path("runNo").asInt()).isEqualTo(2);
        assertThat(overview.path("startedAt").asString()).matches(ISO_MILLIS);
        assertThat(overview.path("stopRequested").asBoolean()).isFalse();
        JsonNode budget = overview.path("budget");
        assertThat(budget.path("scope").asString()).isEqualTo("ACTIVE_RUN");
        assertThat(budget.path("capabilityCallsUsed").asInt()).isZero();
        assertThat(budget.path("capabilityCallsLimit").asInt()).isEqualTo(12);
        assertThat(budget.path("remainingCapabilityCalls").asInt()).isEqualTo(12);
        // 已收束的 run 以本轮 Diagnosis 时间为止
        assertThat(budget.path("durationSeconds").asLong()).isEqualTo(125);
        assertThat(budget.path("durationLimitSeconds").asInt()).isEqualTo(480);
        assertThat(overview.path("hypothesisCount").asLong()).isEqualTo(2);
        assertThat(overview.path("observationCount").asLong()).isEqualTo(2);
        assertThat(overview.path("evidenceCount").asLong()).isEqualTo(3);
        assertThat(overview.path("diagnosisVersions").asLong()).isEqualTo(2);
        assertThat(overview.has("id") || overview.has("investigationId") || overview.has("incidentId"))
                .isFalse();

        JsonNode hypothesisList = get(base + "/investigation/hypotheses").body().path("data");
        assertThat(ids(hypothesisList)).containsExactly(h1, h2);
        assertThat(hypothesisList.path(0).path("status").asString()).isEqualTo("SUPPORTED");
        assertThat(hypothesisList.path(1).path("status").asString()).isEqualTo("REFUTED");

        Response all = get(base + "/investigation/observations?page=0&size=1");
        assertThat(ids(all.body().path("data"))).containsExactly(lag);
        assertThat(all.body().path("page").path("totalElements").asLong()).isEqualTo(2);
        assertThat(all.body().path("page").path("totalPages").asLong()).isEqualTo(2);
        JsonNode first = all.body().path("data").path(0);
        assertThat(first.path("resource").path("resourceKey").asString()).isEqualTo("statistics-stream");
        assertThat(first.path("resource").path("name").asString()).isEqualTo("Statistics Stream");
        assertThat(first.has("payload")).isFalse();
        assertThat(ids(get(base + "/investigation/observations?resourceKey=statistics-stream")
                        .body()
                        .path("data")))
                .containsExactly(lag);
        assertThat(ids(get(base + "/investigation/observations?kind=SERVICE_STATUS")
                        .body()
                        .path("data")))
                .containsExactly(consumer);
        assertThat(get(base + "/investigation/observations?resourceKey=Statistics-Stream")
                        .body()
                        .path("page")
                        .path("totalElements")
                        .asLong())
                .isZero();
        assertError(get(base + "/investigation/observations?kind=SOMETHING"), 400, "REQUEST_VALIDATION_FAILED");
        assertError(get(base + "/investigation/observations?size=101"), 400, "REQUEST_VALIDATION_FAILED");

        JsonNode detail =
                get(base + "/investigation/observations/" + lag).body().path("data");
        assertThat(detail.path("schema").path("name").asString()).isEqualTo("queue.inspect.result");
        assertThat(detail.path("schema").path("version").asInt()).isOne();
        assertThat(detail.path("payload").isObject()).isTrue();
        assertThat(detail.path("payload").path("lag").asInt()).isEqualTo(2180);
        assertThat(detail.path("window").path("start").asString()).matches(ISO_MILLIS);
        assertThat(detail.path("invocation").path("capabilityKey").asString()).isEqualTo("queue.inspect");
        assertThat(detail.path("invocation").path("runNo").asInt()).isOne();
        assertThat(get(base + "/investigation/observations/" + consumer)
                        .body()
                        .path("data")
                        .path("window")
                        .isNull())
                .isTrue();
        // 恢复观测不是调查事实
        assertError(get(base + "/investigation/observations/" + recovery), 404, "RESOURCE_NOT_FOUND");

        JsonNode evidenceList = get(base + "/investigation/evidence").body().path("data");
        assertThat(ids(evidenceList)).containsExactly(e1, e2, e3);
        assertThat(evidenceList.path(0).path("relation").asString()).isEqualTo("SUPPORTS");
        assertThat(evidenceList.path(0).path("observation").path("summary").asString())
                .isEqualTo("积压 2180");
        assertThat(evidenceList.path(0).path("hypothesis").path("title").asString())
                .isEqualTo("Statistics Consumer 已停止");

        JsonNode history = get(base + "/diagnoses").body().path("data");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.path(0).path("version").asInt()).isOne();
        assertThat(history.path(0).path("conclusionType").asString()).isEqualTo("PRIMARY_CAUSE_IDENTIFIED");
        assertThat(history.path(1).path("conclusionType").asString()).isEqualTo("UNDETERMINED");

        JsonNode v1 = get(base + "/diagnoses/1").body().path("data");
        assertThat(v1.path("runNo").asInt()).isOne();
        assertThat(v1.path("primaryHypothesis").path("id").asLong()).isEqualTo(h1);
        assertThat(v1.path("terminationReason").asString()).isEqualTo("AGENT_COMPLETED");
        assertThat(longs(v1.path("evidenceIds"))).containsExactly(e1);
        assertThat(ids(v1.path("evidence"))).containsExactly(e1);
        JsonNode v2Detail = get(base + "/diagnoses/2").body().path("data");
        assertThat(v2Detail.path("runNo").asInt()).isEqualTo(2);
        assertThat(v2Detail.path("primaryHypothesis").isNull()).isTrue();
        assertThat(longs(v2Detail.path("evidenceIds"))).containsExactly(e2, e3);
        assertError(get(base + "/diagnoses/3"), 404, "DIAGNOSIS_NOT_FOUND");
        assertError(get(base + "/diagnoses/0"), 400, "REQUEST_VALIDATION_FAILED");

        // 没有写入口（05 §51、§54）
        assertThat(post(base + "/investigation/hypotheses", "{\"title\": \"x\"}")
                        .status())
                .isGreaterThanOrEqualTo(400);
        assertThat(post(base + "/investigation/evidence", "{}").status()).isGreaterThanOrEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hypothesis", Integer.class))
                .isEqualTo(2);
    }

    private String createIncident() throws Exception {
        Response created = post("/api/v1/incidents", """
                {"systemKey": "shortlink-platform", "title": "统计数据停止更新", "impactSummary": "统计延迟",
                 "affectedResourceKeys": ["statistics-stream"]}
                """);
        assertThat(created.status()).isEqualTo(201);
        return created.body().path("data").path("incidentKey").asString();
    }

    private long observation(
            long incidentId, Long investigationId, String resourceKey, String kind, String summary, boolean window) {
        boolean recovery = investigationId == null;
        long resourceId =
                jdbc.queryForObject("SELECT id FROM managed_resource WHERE resource_key = ?", Long.class, resourceKey);
        String correlation = "obs-" + summary.hashCode();
        jdbc.update(
                "INSERT INTO capability_invocation (incident_id, investigation_id, recovery_verification_id, run_no,"
                        + " criterion_key, sample_index, capability_key, managed_resource_id, status,"
                        + " request_schema_name, request_schema_version, request_payload, response_schema_name,"
                        + " response_schema_version, response_payload, started_at, finished_at, duration_ms,"
                        + " correlation_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'queue.inspect', ?,"
                        + " 'SUCCEEDED', 'queue.inspect.request', 1, '{}', 'queue.inspect.result', 1, '{}',"
                        + " UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 42, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))",
                incidentId,
                investigationId,
                recovery ? 9001L : null,
                recovery ? null : 1,
                recovery ? "stream-lag-drained" : null,
                recovery ? 1 : null,
                resourceId,
                correlation);
        long invocationId = jdbc.queryForObject(
                "SELECT id FROM capability_invocation WHERE correlation_id = ?", Long.class, correlation);
        Instant observed = Instant.parse("2026-09-27T08:00:00.000Z");
        jdbc.update(
                "INSERT INTO observation (incident_id, investigation_id, recovery_verification_id,"
                        + " capability_invocation_id, managed_resource_id, observation_kind, schema_name,"
                        + " schema_version, payload, summary, observed_at, window_start, window_end, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'queue.inspect.result', 1, '{\"lag\": 2180, \"pending\": 3}', ?,"
                        + " ?, ?, ?, UTC_TIMESTAMP(3))",
                incidentId,
                investigationId,
                recovery ? 9001L : null,
                invocationId,
                resourceId,
                kind,
                summary,
                utc(observed),
                window ? utc(observed.minusSeconds(900)) : null,
                window ? utc(observed) : null);
        return jdbc.queryForObject(
                "SELECT id FROM observation WHERE capability_invocation_id = ?", Long.class, invocationId);
    }

    private long propose(long incidentId, String title) {
        return hypotheses
                .proposeHypothesis(new ProposeHypothesisCommand(incidentId, title, null))
                .id();
    }

    private long link(
            long incidentId,
            long observationId,
            long hypothesisId,
            EvidenceRelation relation,
            HypothesisStatus update) {
        return evidence.createEvidenceLink(new LinkEvidenceCommand(
                        incidentId, observationId, hypothesisId, relation, relation + " 的理由", update))
                .evidence()
                .id();
    }

    private Diagnosis diagnose(
            long incidentId,
            int runNo,
            DiagnosisConclusionType type,
            Long primary,
            List<Long> evidenceIds,
            TerminationReason reason) {
        return diagnoses.createDiagnosis(new CreateDiagnosisCommand(
                incidentId, runNo, new DiagnosisDraft(type, primary, "诊断摘要", "统计延迟", evidenceIds), reason));
    }

    /** 与应用一致：DATETIME 列按 UTC 的 LocalDateTime 写入，不受 JVM 时区影响。 */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asLong()));
        return values;
    }

    private static void assertError(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body().path("code").asString()).isEqualTo(code);
    }

    private Response action(String key, String action, long expectedVersion) throws Exception {
        return post(
                "/api/v1/incidents/" + key + "/actions/" + action, "{\"expectedVersion\": " + expectedVersion + "}");
    }

    private Response get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private Response post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), json.readTree(response.body()));
    }

    private record Response(int status, JsonNode body) {}
}
