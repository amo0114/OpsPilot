package io.github.ismoyuan.opspilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Heartbeat（05 §66）与唤醒丢失后的自愈（07 §73）：heartbeat 不产生 TimelineEvent；绕过应用直接提交、没有任何提交后唤醒的事件，在下一次
 * heartbeat 的顺带补读中送达。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "opspilot.sse.heartbeat-interval=1s")
@ActiveProfiles("demo")
@Testcontainers
class IncidentEventStreamHeartbeatTest {

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

    @MockitoBean
    WorkDispatcher dispatcher;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void heartbeatsKeepTheStreamAliveAndCatchUpAMissedWake() throws Exception {
        HttpResponse<String> created = http.send(
                HttpRequest.newBuilder(uri("/api/v1/incidents"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"systemKey\": \"shortlink-platform\", \"title\": \"统计积压\","
                                        + " \"impactSummary\": \"统计延迟\", \"affectedResourceKeys\": []}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String key =
                json.readTree(created.body()).path("data").path("incidentKey").asString();
        long incidentId = jdbc.queryForObject("SELECT id FROM incident WHERE incident_key = ?", Long.class, key);

        try (SseClient stream = SseClient.open(http, uri("/api/v1/incidents/" + key + "/events"), null)) {
            assertThat(stream.next(Duration.ofSeconds(5)).name()).isEqualTo("timeline");
            assertThat(stream.next(Duration.ofSeconds(5)).name()).isEqualTo("incident-state");
            SseClient.Event heartbeat = stream.nextIncludingHeartbeat(Duration.ofSeconds(5));
            assertThat(heartbeat.name()).isEqualTo("heartbeat");
            assertThat(heartbeat.id()).isNull();
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM incident_timeline_event WHERE incident_id = ?",
                            Integer.class,
                            incidentId))
                    .isEqualTo(1);

            // 直接提交、不经应用：没有提交后唤醒，只能由 heartbeat 的补读送达
            jdbc.update(
                    "INSERT INTO incident_timeline_event (incident_id, event_type, occurred_at, actor_type, summary,"
                            + " payload, created_at) VALUES (?, 'OBSERVATION_RECORDED', UTC_TIMESTAMP(3), 'SYSTEM',"
                            + " '错过唤醒', '{\"schemaName\": \"test.probe\", \"schemaVersion\": 1}', UTC_TIMESTAMP(3))",
                    incidentId);
            SseClient.Event missed = stream.next(Duration.ofSeconds(5));
            assertThat(missed.name()).isEqualTo("timeline");
            assertThat(json.readTree(missed.data())
                            .path("event")
                            .path("summary")
                            .asString())
                    .isEqualTo("错过唤醒");
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
