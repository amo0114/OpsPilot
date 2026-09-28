package io.github.ismoyuan.opspilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.dispatch.InvestigationWorker;
import io.github.ismoyuan.opspilot.application.investigation.orchestration.InvestigationOrchestrator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * 真实 HTTP＋真实 MySQL（demo Seed）上的 Incident API 契约（05 §8～§11、§20～§28、§33、§93～§94，08 TASK-020）。
 * 只验证已实现的动作与事务合同；202 不表示后台调查已完成（调查 Worker 在后台独立运行，未配置 AI 时按失败阈值退出）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class IncidentApiContractTest {

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
    InvestigationWorker investigationWorker;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearIncidents() {
        jdbc.update("DELETE FROM incident_timeline_event");
        jdbc.update("DELETE FROM investigation");
        jdbc.update("DELETE FROM incident_affected_resource");
        jdbc.update("DELETE FROM incident");
    }

    /** 创建 → 列表 → 详情 → Start → Stop（幂等）→ Continue → Cancel 的状态、版本、HTTP 码与响应结构。 */
    @Test
    void lifecycleActionsFollowTheApiContract() throws Exception {
        Response created = post("/api/v1/incidents", """
                {"systemKey": "shortlink-platform", "title": "短链接跳转明显变慢",
                 "description": "用户反馈短链接跳转耗时显著增加。", "impactSummary": "短链接跳转速度明显下降",
                 "startedAt": "2026-09-25T07:10:00.000Z", "affectedResourceKeys": ["redirect-service"]}
                """, "req_contract-create");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("X-Request-Id")).isEqualTo("req_contract-create");
        assertThat(created.body().path("requestId").asString()).isEqualTo("req_contract-create");
        JsonNode data = created.body().path("data");
        String key = data.path("incidentKey").asString();
        assertThat(key).matches("INC-\\d{8}-0001");
        assertThat(data.path("status").asString()).isEqualTo("CREATED");
        assertThat(data.path("version").asLong()).isZero();

        Response list = get("/api/v1/incidents?systemKey=shortlink-platform&status=CREATED&page=0&size=20");
        assertThat(list.status()).isEqualTo(200);
        JsonNode item = list.body().path("data").path(0);
        assertThat(item.path("incidentKey").asString()).isEqualTo(key);
        assertThat(item.path("systemName").asString()).isEqualTo("ShortLink Platform");
        assertThat(item.path("impactSummary").asString()).isEqualTo("短链接跳转速度明显下降");
        assertThat(item.path("detectedAt").asString()).matches(ISO_MILLIS);
        assertThat(item.path("updatedAt").asString()).matches(ISO_MILLIS);
        assertThat(item.has("id")).isFalse();
        assertThat(list.body().path("page").path("totalElements").asLong()).isEqualTo(1);
        assertThat(get("/api/v1/incidents?status=INVESTIGATING")
                        .body()
                        .path("data")
                        .size())
                .isZero();

        JsonNode detail = get("/api/v1/incidents/" + key).body().path("data");
        assertThat(detail.path("system").path("systemKey").asString()).isEqualTo("shortlink-platform");
        assertThat(detail.path("statusLabel").asString()).isEqualTo("待调查");
        assertThat(detail.path("impact").path("startedAt").asString()).isEqualTo("2026-09-25T07:10:00.000Z");
        assertThat(detail.path("affectedResources").path(0).path("resourceKey").asString())
                .isEqualTo("redirect-service");
        assertThat(detail.path("investigation").isNull()).isTrue();
        assertThat(detail.has("id")).isFalse();
        assertThat(detail.has("availableActions")).isFalse();

        Response started = action(key, "start-investigation", 0);
        assertThat(started.status()).isEqualTo(202);
        assertThat(started.body().path("data").path("status").asString()).isEqualTo("INVESTIGATING");
        assertThat(started.body().path("data").path("version").asLong()).isEqualTo(1);
        assertThat(started.body().path("data").path("investigationStarted").asBoolean())
                .isTrue();

        Response restarted = action(key, "start-investigation", 1);
        assertThat(restarted.status()).isEqualTo(409);
        assertThat(restarted.body().path("code").asString()).isEqualTo("INCIDENT_STATE_CONFLICT");
        assertThat(restarted.body().path("details").path("currentStatus").asString())
                .isEqualTo("INVESTIGATING");

        Response stopped = action(key, "stop-investigation", 1);
        Response stoppedAgain = action(key, "stop-investigation", 1);
        for (Response stop : new Response[] {stopped, stoppedAgain}) {
            assertThat(stop.status()).isEqualTo(202);
            JsonNode stopData = stop.body().path("data");
            assertThat(stopData.path("status").asString()).isEqualTo("INVESTIGATING");
            assertThat(stopData.path("version").asLong()).isEqualTo(2);
            assertThat(stopData.path("runNo").asInt()).isEqualTo(1);
            assertThat(stopData.path("stopRequested").asBoolean()).isTrue();
        }
        assertThat(get("/api/v1/incidents/" + key)
                        .body()
                        .path("data")
                        .path("investigation")
                        .path("stopRequested")
                        .asBoolean())
                .isTrue();

        // 收束为 DIAGNOSED 属于调查 Worker（TASK-041/042）；这里直接设置事实以验证 Continue 的 HTTP 合同
        jdbc.update("UPDATE incident SET status = 'DIAGNOSED', lock_version = 3 WHERE incident_key = ?", key);
        Response continued = action(key, "continue-investigation", 3);
        assertThat(continued.status()).isEqualTo(202);
        assertThat(continued.body().path("data").path("runNo").asInt()).isEqualTo(2);
        assertThat(continued.body().path("data").path("stopRequested").asBoolean())
                .isFalse();
        assertThat(continued.body().path("data").path("version").asLong()).isEqualTo(4);

        Response cancelled = post(
                "/api/v1/incidents/" + key + "/actions/cancel",
                "{\"expectedVersion\": 4, \"reason\": \"确认是测试数据，停止处理。\"}",
                null);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().path("data").path("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.body().path("data").path("version").asLong()).isEqualTo(5);
        assertThat(action(key, "continue-investigation", 5).status()).isEqualTo(409);

        assertThat(jdbc.queryForList(
                        "SELECT event_type FROM incident_timeline_event t JOIN incident i ON i.id = t.incident_id"
                                + " WHERE i.incident_key = ? ORDER BY t.id",
                        String.class,
                        key))
                .containsExactly(
                        "INCIDENT_CREATED",
                        "INVESTIGATION_STARTED",
                        "INVESTIGATION_STOP_REQUESTED",
                        "INVESTIGATION_STARTED",
                        "INCIDENT_CANCELLED");
    }

    /** 应用装配使用真实调查编排，而不是基础设施模块的未装配兜底（08 TASK-040）。 */
    @Test
    void investigationWorkerIsTheOrchestrator() {
        assertThat(investigationWorker).isInstanceOf(InvestigationOrchestrator.class);
    }

    /** 400/404/409/422 与统一错误包络（05 §9、§93～§94）。 */
    @Test
    void errorsUseTheStandardEnvelopeAndStatusCodes() throws Exception {
        assertError(
                post("/api/v1/incidents", createBody("shortlink-platform", "\"标题\"", "[\"billing-service\"]"), null),
                422,
                "RESOURCE_NOT_IN_SYSTEM");
        assertError(
                post("/api/v1/incidents", createBody("missing-platform", "\"标题\"", "[]"), null),
                404,
                "SYSTEM_NOT_FOUND");
        Response blankTitle = post("/api/v1/incidents", createBody("shortlink-platform", "\" \"", "[]"), null);
        assertError(blankTitle, 400, "REQUEST_VALIDATION_FAILED");
        assertThat(blankTitle.body().path("details").path("field").asString()).isEqualTo("title");
        assertError(
                post(
                        "/api/v1/incidents",
                        "{\"systemKey\": \"shortlink-platform\", \"title\": \"t\", \"impactSummary\": \"i\","
                                + " \"severity\": \"HIGH\"}",
                        null),
                400,
                "REQUEST_VALIDATION_FAILED");

        String key = post("/api/v1/incidents", createBody("shortlink-platform", "\"标题\"", "[]"), null)
                .body()
                .path("data")
                .path("incidentKey")
                .asString();
        assertError(
                post("/api/v1/incidents/" + key + "/actions/start-investigation", "{}", null),
                400,
                "REQUEST_VALIDATION_FAILED");
        assertError(
                post("/api/v1/incidents/" + key + "/actions/start-investigation", "{\"expectedVersion\": -1}", null),
                400,
                "REQUEST_VALIDATION_FAILED");
        // 小数版本不得被截断为 0 后执行迁移（B03-R1 P1）；Start 与 Cancel 共用同一整数版本字段绑定
        for (String action : new String[] {"start-investigation", "cancel"}) {
            assertError(
                    post("/api/v1/incidents/" + key + "/actions/" + action, "{\"expectedVersion\": 0.9}", null),
                    400,
                    "REQUEST_VALIDATION_FAILED");
        }
        assertThat(jdbc.queryForMap("SELECT status, lock_version FROM incident WHERE incident_key = ?", key))
                .containsEntry("status", "CREATED")
                .containsEntry("lock_version", java.math.BigInteger.ZERO);
        assertThat(jdbc.queryForList(
                        "SELECT t.event_type FROM incident_timeline_event t JOIN incident i ON i.id = t.incident_id"
                                + " WHERE i.incident_key = ?",
                        String.class,
                        key))
                .containsExactly("INCIDENT_CREATED");
        Response stale = action(key, "start-investigation", 7);
        assertError(stale, 409, "INCIDENT_VERSION_CONFLICT");
        assertThat(stale.body().path("details").path("version").asLong()).isZero();
        assertError(action(key, "stop-investigation", 0), 409, "INCIDENT_STATE_CONFLICT");
        assertError(action("INC-20990101-0001", "start-investigation", 0), 404, "INCIDENT_NOT_FOUND");
        assertError(get("/api/v1/incidents/" + key.toLowerCase()), 404, "INCIDENT_NOT_FOUND");
        assertError(get("/api/v1/incidents?status=OPEN"), 400, "REQUEST_VALIDATION_FAILED");
        assertError(get("/api/v1/incidents?size=0"), 400, "REQUEST_VALIDATION_FAILED");
    }

    private static String createBody(String systemKey, String titleJson, String resourcesJson) {
        return "{\"systemKey\": \"" + systemKey + "\", \"title\": " + titleJson
                + ", \"impactSummary\": \"影响\", \"affectedResourceKeys\": " + resourcesJson + "}";
    }

    private static void assertError(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body().path("code").asString()).isEqualTo(code);
        assertThat(response.body().path("message").asString()).isNotBlank();
        assertThat(response.body().path("requestId").asString()).isEqualTo(response.header("X-Request-Id"));
    }

    private Response action(String key, String action, long expectedVersion) throws Exception {
        return post(
                "/api/v1/incidents/" + key + "/actions/" + action,
                "{\"expectedVersion\": " + expectedVersion + "}",
                null);
    }

    private Response get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private Response post(String path, String body, String requestId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        return send(builder.build());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), json.readTree(response.body()), response);
    }

    private record Response(int status, JsonNode body, HttpResponse<String> raw) {
        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }
}
