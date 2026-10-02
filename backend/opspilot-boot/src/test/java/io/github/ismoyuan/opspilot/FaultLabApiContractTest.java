package io.github.ismoyuan.opspilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.faultlab.FaultConfirmation;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjection;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjector;
import io.github.ismoyuan.opspilot.application.faultlab.FaultTarget;
import io.github.ismoyuan.opspilot.application.faultlab.StatisticsConsumerStopInjector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实 HTTP＋真实 MySQL（demo Seed）上的 Fault Lab API（05 §68～§73、§93～§94，08 TASK-092）。redis-latency 由测试注入器确认生效
 * （真实注入器属 TASK-094），mysql-slow-query 没有注入器；statistics-consumer-stop 的真实注入器（TASK-093）已装配，但这里不调用。响应与故障详情都不含 Ground Truth；没有 Ground Truth 读取接口。
 */
// demo profile 的真实 redis-latency 注入器与本测试的同场景注入器互斥：关闭真实注入器
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "opspilot.fault-lab.redis-latency.enabled=false")
@ActiveProfiles("demo")
@Testcontainers
class FaultLabApiContractTest {

    static final List<String> ANSWER_MARKERS =
            List.of("REDIS_NETWORK_LATENCY", "groundTruth", "ground_truth", "latencyMs", "cause");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 立即确认生效的注入器（只用于测试 HTTP 契约）。 */
    @TestConfiguration
    static class TestInjector {

        @Bean
        FaultInjector redisLatencyInjector() {
            return new FaultInjector() {
                @Override
                public String scenarioKey() {
                    return "redis-latency";
                }

                @Override
                public boolean controls(String systemKey, String targetResourceKey) {
                    return true;
                }

                @Override
                public FaultInjection inject(FaultTarget target) {
                    return FaultInjection.startedAt(Instant.now().minusSeconds(3));
                }

                @Override
                public FaultConfirmation verifyInjected(FaultTarget target) {
                    return FaultConfirmation.detectedAt(Instant.now());
                }

                @Override
                public void reset(FaultTarget target) {}
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    /** 后台调查不会改变这里断言的 Incident。 */
    @MockitoBean
    WorkDispatcher dispatcher;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clear() {
        for (String table : List.of(
                "fault_experiment",
                "incident_timeline_event",
                "investigation",
                "incident_affected_resource",
                "incident")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Autowired
    List<FaultInjector> injectors;

    /** demo profile 装配真实的 S3 注入器（08 TASK-093）；本测试不调用它，以免触及本机 Docker。 */
    @Test
    void theDemoProfileWiresTheRealConsumerStopInjector() {
        assertThat(injectors)
                .extracting(FaultInjector::scenarioKey)
                .containsExactlyInAnyOrder("redis-latency", "statistics-consumer-stop");
        assertThat(injectors).anyMatch(StatisticsConsumerStopInjector.class::isInstance);
    }

    @Test
    void scenariosListTheirTargetsWithoutAnswers() throws Exception {
        Response response = get("/api/v1/fault-lab/scenarios");

        assertThat(response.status()).isEqualTo(200);
        JsonNode scenarios = response.body().path("data");
        assertThat(scenarios.valueStream().map(s -> s.path("scenarioKey").asString()))
                .containsExactly("redis-latency", "mysql-slow-query", "statistics-consumer-stop");
        assertThat(scenarios.valueStream().map(s -> s.path("targetResourceKey").asString()))
                .containsExactly("shortlink-redis", "shortlink-mysql", "statistics-consumer");
        assertThat(scenarios.path(0).propertyNames())
                .containsExactlyInAnyOrder("scenarioKey", "name", "description", "targetResourceKey");
        assertNoAnswer(response.raw().body());
    }

    /**
     * 201：ACTIVE 实验与 CREATED Incident（含 availableActions）；Incident 只描述症状；同系统再次注入 409；Reset 200 只恢复环境，Incident
     * 仍为 CREATED；没有 Ground Truth 读取路由。
     */
    @Test
    void injectThenResetFollowTheContract() throws Exception {
        Response injected = post(
                "/api/v1/fault-lab/scenarios/redis-latency/actions/inject", "{\"systemKey\": \"shortlink-platform\"}");

        assertThat(injected.status()).isEqualTo(201);
        assertNoAnswer(injected.raw().body());
        JsonNode data = injected.body().path("data");
        long experimentId = data.path("experimentId").asLong();
        assertThat(data.path("status").asString()).isEqualTo("ACTIVE");
        String incidentKey = data.path("incident").path("incidentKey").asString();
        assertThat(incidentKey).matches("INC-\\d{8}-\\d{4}");
        assertThat(data.path("incident").path("status").asString()).isEqualTo("CREATED");
        assertThat(data.path("incident").path("version").asLong()).isZero();
        assertThat(data.path("incident").path("availableActions").valueStream().map(JsonNode::asString))
                .containsExactly("START_INVESTIGATION", "CANCEL_INCIDENT");

        Response detail = get("/api/v1/incidents/" + incidentKey);
        assertThat(detail.body().path("data").path("title").asString()).isEqualTo("短链接跳转明显变慢");
        assertNoAnswer(detail.raw().body());
        assertThat(detail.raw().body()).doesNotContain("redis-latency").doesNotContain("FAULT_LAB");

        assertError(
                post(
                        "/api/v1/fault-lab/scenarios/redis-latency/actions/inject",
                        "{\"systemKey\": \"shortlink-platform\"}"),
                409,
                "FAULT_EXPERIMENT_STATE_CONFLICT");

        Response reset = post("/api/v1/fault-lab/experiments/" + experimentId + "/actions/reset", "");
        assertThat(reset.status()).isEqualTo(200);
        assertThat(reset.body().path("data").path("experimentId").asLong()).isEqualTo(experimentId);
        assertThat(reset.body().path("data").path("status").asString()).isEqualTo("RESET");
        assertThat(get("/api/v1/incidents/" + incidentKey)
                        .body()
                        .path("data")
                        .path("status")
                        .asString())
                .isEqualTo("CREATED");
        assertError(
                post("/api/v1/fault-lab/experiments/" + experimentId + "/actions/reset", ""),
                409,
                "FAULT_EXPERIMENT_STATE_CONFLICT");

        assertThat(get("/api/v1/fault-lab/experiments/" + experimentId + "/ground-truth")
                        .status())
                .isEqualTo(404);
        assertThat(get("/api/v1/fault-lab/experiments/" + experimentId).status())
                .isEqualTo(404);
    }

    /** 05 §93～§94 的错误码与 HTTP 状态；拒绝时不创建实验。 */
    @Test
    void errorsUseTheFaultLabCodes() throws Exception {
        jdbc.update("INSERT IGNORE INTO managed_system (system_key, name, environment, status, created_at, updated_at)"
                + " VALUES ('prod-platform', 'Prod', 'PRODUCTION', 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))");

        assertError(
                post("/api/v1/fault-lab/scenarios/disk-full/actions/inject", "{\"systemKey\": \"shortlink-platform\"}"),
                404,
                "FAULT_SCENARIO_NOT_FOUND");
        assertError(
                post("/api/v1/fault-lab/scenarios/redis-latency/actions/inject", "{\"systemKey\": \"prod-platform\"}"),
                422,
                "FAULT_SCENARIO_NOT_ALLOWED");
        // 环境先于注入器可用性（B33-R1 P2）：PRODUCTION 上没有注入器的场景同样是 422
        assertError(
                post(
                        "/api/v1/fault-lab/scenarios/mysql-slow-query/actions/inject",
                        "{\"systemKey\": \"prod-platform\"}"),
                422,
                "FAULT_SCENARIO_NOT_ALLOWED");
        assertError(
                post("/api/v1/fault-lab/scenarios/redis-latency/actions/inject", "{\"systemKey\": \"missing\"}"),
                404,
                "SYSTEM_NOT_FOUND");
        assertError(
                post("/api/v1/fault-lab/scenarios/redis-latency/actions/inject", "{}"),
                400,
                "REQUEST_VALIDATION_FAILED");
        assertError(
                post(
                        "/api/v1/fault-lab/scenarios/mysql-slow-query/actions/inject",
                        "{\"systemKey\": \"shortlink-platform\"}"),
                502,
                "FAULT_INJECTION_FAILED");
        assertError(post("/api/v1/fault-lab/experiments/999999/actions/reset", ""), 404, "RESOURCE_NOT_FOUND");
        assertError(post("/api/v1/fault-lab/experiments/abc/actions/reset", ""), 400, "REQUEST_VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fault_experiment", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident", Integer.class))
                .isZero();
    }

    private static void assertNoAnswer(String body) {
        for (String marker : ANSWER_MARKERS) {
            assertThat(body).as(marker).doesNotContain(marker);
        }
    }

    private static void assertError(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body().path("code").asString()).isEqualTo(code);
        assertThat(response.body().path("requestId").asString()).isNotBlank();
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

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        return new Response(response.statusCode(), body, response);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private record Response(int status, JsonNode body, HttpResponse<String> raw) {}
}
