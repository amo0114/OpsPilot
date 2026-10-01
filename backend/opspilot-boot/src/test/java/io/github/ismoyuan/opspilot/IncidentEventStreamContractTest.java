package io.github.ismoyuan.opspilot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.dispatch.WorkDispatcher;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.timeline.TimelineRepository;
import io.github.ismoyuan.opspilot.domain.timeline.NewTimelineEvent;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineActorType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelineEventType;
import io.github.ismoyuan.opspilot.domain.timeline.TimelinePayload;
import io.github.ismoyuan.opspilot.web.sse.IncidentSseHub;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实 HTTP＋真实 MySQL 上的 Incident 事件流（05 §62～§67、08 TASK-087～089、09 ACC-FINAL-15）。Heartbeat 间隔设为 1 小时，使这里
 * 收到的每个事件都只能来自提交后的唤醒，而不是 heartbeat 的顺带补读（后者由 {@link IncidentEventStreamHeartbeatTest} 覆盖）。
 * 派发器为替身，后台调查不会写入额外事件。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "opspilot.sse.heartbeat-interval=1h")
@ActiveProfiles("demo")
@Testcontainers
class IncidentEventStreamContractTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static final Duration QUIET = Duration.ofMillis(800);

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
    IncidentRepository incidents;

    @Autowired
    TimelineRepository timeline;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    IncidentSseHub hub;

    @MockitoBean
    WorkDispatcher dispatcher;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearIncidents() {
        jdbc.update("DELETE FROM incident_timeline_event");
        jdbc.update("DELETE FROM investigation");
        jdbc.update("DELETE FROM incident_affected_resource");
        jdbc.update("DELETE FROM incident");
    }

    /**
     * 没有游标时从第一条事件补发，随后是 incident-state；之后每次已提交的动作都推送 timeline（id 即 Timeline id，载荷含当前状态）与
     * 变化后的 incident-state（版本、availableActions 由 Java 计算）。
     */
    @Test
    void theStreamReplaysFromTheStartThenPushesCommittedChanges() throws Exception {
        String key = createIncident();
        try (SseClient stream = open(key, null, null)) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.contentType()).startsWith("text/event-stream");

            SseClient.Event created = stream.next(WAIT);
            assertThat(created.name()).isEqualTo("timeline");
            JsonNode createdData = data(created);
            assertThat(createdData.path("incidentKey").asString()).isEqualTo(key);
            assertThat(createdData.path("incidentStatus").asString()).isEqualTo("CREATED");
            assertThat(createdData.path("event").path("eventType").asString()).isEqualTo("INCIDENT_CREATED");
            assertThat(createdData.path("event").path("summary").asString()).startsWith("创建故障：");
            assertThat(createdData.path("event").path("id").asLong()).isEqualTo(Long.parseLong(created.id()));
            assertState(stream.next(WAIT), "CREATED", 0, "START_INVESTIGATION", "CANCEL_INCIDENT");

            assertThat(action(key, "start-investigation", 0)).isEqualTo(202);
            SseClient.Event started = stream.next(WAIT);
            assertThat(started.name()).isEqualTo("timeline");
            assertThat(Long.parseLong(started.id())).isGreaterThan(Long.parseLong(created.id()));
            assertThat(data(started).path("event").path("eventType").asString()).isEqualTo("INVESTIGATION_STARTED");
            assertThat(data(started).path("incidentStatus").asString()).isEqualTo("INVESTIGATING");
            assertState(stream.next(WAIT), "INVESTIGATING", 1, "STOP_INVESTIGATION", "CANCEL_INCIDENT");

            assertThat(action(key, "stop-investigation", 1)).isEqualTo(202);
            assertThat(data(stream.next(WAIT)).path("event").path("eventType").asString())
                    .isEqualTo("INVESTIGATION_STOP_REQUESTED");
            assertState(stream.next(WAIT), "INVESTIGATING", 2, "CANCEL_INCIDENT");
            assertThat(stream.drain(QUIET)).isEmpty();
        }
    }

    /**
     * 先 GET Snapshot 再以 lastTimelineEventId 连接：不重放 Snapshot 已包含的事件，只补其后的；浏览器重连带 Last-Event-ID 时它优先于
     * URL 中首次连接的游标，从该 id 之后按顺序补发，不重复、不遗漏。
     */
    @Test
    void theSnapshotCursorAndLastEventIdResumeWithoutGapsOrRepeats() throws Exception {
        String key = createIncident();
        assertThat(action(key, "start-investigation", 0)).isEqualTo(202);
        List<Long> ids = timelineIds(key);
        long snapshotCursor = get("/api/v1/incidents/" + key)
                .path("data")
                .path("lastTimelineEventId")
                .asLong();
        assertThat(snapshotCursor).isEqualTo(ids.getLast());

        long stopped;
        try (SseClient stream = open(key, snapshotCursor, null)) {
            assertState(stream.next(WAIT), "INVESTIGATING", 1, "STOP_INVESTIGATION", "CANCEL_INCIDENT");
            assertThat(action(key, "stop-investigation", 1)).isEqualTo(202);
            SseClient.Event stop = stream.next(WAIT);
            assertThat(stop.name()).isEqualTo("timeline");
            stopped = Long.parseLong(stop.id());
            assertThat(stopped).isGreaterThan(snapshotCursor);
        }

        try (SseClient resumed = open(key, snapshotCursor, Long.toString(ids.getFirst()))) {
            List<SseClient.Event> events = resumed.drain(QUIET);
            assertThat(events)
                    .extracting(SseClient.Event::name)
                    .containsExactly("timeline", "timeline", "incident-state");
            assertThat(events.subList(0, 2))
                    .extracting(event -> Long.parseLong(event.id()))
                    .containsExactly(ids.getLast(), stopped);
        }
    }

    /**
     * 未提交的追加不会推送（即使事件流正在等待）；回滚的追加永远不会出现；之后提交的追加正常推送（ENG-INV-016、08 TASK-088）。
     */
    @Test
    void uncommittedAndRolledBackAppendsAreNeverPushed() throws Exception {
        String key = createIncident();
        long incidentId = incidentId(key);
        try (SseClient stream = open(key, null, null)) {
            assertThat(stream.drain(QUIET))
                    .extracting(SseClient.Event::name)
                    .containsExactly("timeline", "incident-state");

            CountDownLatch appended = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> rolledBack =
                    CompletableFuture.runAsync(() -> transaction().executeWithoutResult(tx -> {
                        incidents.findByIdForUpdate(incidentId).orElseThrow();
                        append(incidentId, "未提交即回滚");
                        appended.countDown();
                        await(release);
                        tx.setRollbackOnly();
                    }));
            assertThat(appended.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stream.drain(QUIET)).isEmpty();
            release.countDown();
            rolledBack.get(5, TimeUnit.SECONDS);
            assertThat(stream.drain(QUIET)).isEmpty();

            transaction().executeWithoutResult(tx -> {
                incidents.findByIdForUpdate(incidentId).orElseThrow();
                append(incidentId, "已提交");
            });
            SseClient.Event committed = stream.next(WAIT);
            assertThat(data(committed).path("event").path("summary").asString()).isEqualTo("已提交");
        }
        assertThat(jdbc.queryForList("SELECT summary FROM incident_timeline_event", String.class))
                .doesNotContain("未提交即回滚");
    }

    /**
     * ACC-FINAL-15：A 先持 Incident 行锁追加，B 等锁；A 提交后它的唤醒被人为推迟到 B 提交并唤醒之后。发送端由 B 的唤醒从游标补读，
     * 按 id 顺序同时送出 A、B；A 迟到的唤醒不再重复发送。行锁使 B 的 id 必然大于 A，游标不会跳过 A。
     */
    @Test
    void commitAndCallbackOrderPerturbationLosesNothing() throws Exception {
        String key = createIncident();
        long incidentId = incidentId(key);
        try (SseClient stream = open(key, null, null)) {
            assertThat(stream.drain(QUIET)).hasSize(2);

            CountDownLatch aAppended = new CountDownLatch(1);
            CountDownLatch commitA = new CountDownLatch(1);
            CountDownLatch releaseAWake = new CountDownLatch(1);
            AtomicBoolean bAppended = new AtomicBoolean();
            CompletableFuture<Void> a =
                    CompletableFuture.runAsync(() -> transaction().executeWithoutResult(tx -> {
                        incidents.findByIdForUpdate(incidentId).orElseThrow();
                        // 先于追加登记：A 的提交后回调排在 SSE 唤醒之前，阻塞即推迟 A 的唤醒
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                await(releaseAWake);
                            }
                        });
                        append(incidentId, "A");
                        aAppended.countDown();
                        await(commitA);
                    }));
            assertThat(aAppended.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> b =
                    CompletableFuture.runAsync(() -> transaction().executeWithoutResult(tx -> {
                        incidents.findByIdForUpdate(incidentId).orElseThrow();
                        append(incidentId, "B");
                        bAppended.set(true);
                    }));
            Thread.sleep(QUIET.toMillis());
            assertThat(bAppended).as("B waits for A's incident row lock").isFalse();

            commitA.countDown();
            b.get(5, TimeUnit.SECONDS);
            List<SseClient.Event> delivered = stream.drain(Duration.ofSeconds(2));
            assertThat(a).isNotDone();
            assertThat(delivered)
                    .filteredOn(event -> "timeline".equals(event.name()))
                    .extracting(
                            event -> data(event).path("event").path("summary").asString())
                    .containsExactly("A", "B");
            List<Long> deliveredIds = delivered.stream()
                    .filter(event -> "timeline".equals(event.name()))
                    .map(event -> Long.parseLong(event.id()))
                    .toList();
            assertThat(deliveredIds.get(0)).isLessThan(deliveredIds.get(1));

            releaseAWake.countDown();
            a.get(5, TimeUnit.SECONDS);
            assertThat(stream.drain(QUIET)).isEmpty();
            assertThat(timelineIds(key).subList(1, 3)).isEqualTo(deliveredIds);
        }
    }

    /** 客户端断开不影响业务提交；断开的连接在下一次发送时被移除（SSE 投递不是提交成功的条件，07 §73）。 */
    @Test
    void aDisconnectedClientDoesNotAffectCommits() throws Exception {
        String key = createIncident();
        int before = hub.connectionCount();
        SseClient stream = open(key, null, null);
        assertThat(stream.drain(QUIET)).hasSize(2);
        assertThat(hub.connectionCount()).isEqualTo(before + 1);
        stream.close();

        assertThat(action(key, "start-investigation", 0)).isEqualTo(202);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (hub.connectionCount() > before && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(hub.connectionCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM incident WHERE incident_key = ?", String.class, key))
                .isEqualTo("INVESTIGATING");
    }

    /** Incident 不存在、游标非法时在建立事件流之前返回标准错误（05 §9）。 */
    @Test
    void unknownIncidentsAndInvalidCursorsAreRejectedBeforeStreaming() throws Exception {
        String key = createIncident();
        assertError(open("INC-20990101-0001", null, null), 404, "INCIDENT_NOT_FOUND");
        assertError(open(key.toLowerCase(), null, null), 404, "INCIDENT_NOT_FOUND");
        assertError(open(key, null, "abc"), 400, "REQUEST_VALIDATION_FAILED");
        assertError(open(key, null, "-1"), 400, "REQUEST_VALIDATION_FAILED");
        assertError(open(key, -1L, null), 400, "REQUEST_VALIDATION_FAILED");
    }

    // ---------------------------------------------------------------- 工具

    /** 测试追加使用的载荷（只在测试中写入）。 */
    record ProbePayload(String note) implements TimelinePayload {

        @Override
        public String schemaName() {
            return "test.probe";
        }

        @Override
        public int schemaVersion() {
            return 1;
        }
    }

    private void append(long incidentId, String summary) {
        timeline.append(new NewTimelineEvent(
                incidentId,
                TimelineEventType.OBSERVATION_RECORDED,
                Instant.now(),
                TimelineActorType.SYSTEM,
                null,
                summary,
                new ProbePayload(summary),
                null));
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private void assertState(SseClient.Event event, String status, long version, String... actions) {
        assertThat(event).isNotNull();
        assertThat(event.name()).isEqualTo("incident-state");
        assertThat(event.id()).isNull();
        JsonNode state = data(event);
        assertThat(state.path("status").asString()).isEqualTo(status);
        assertThat(state.path("version").asLong()).isEqualTo(version);
        assertThat(state.path("availableActions").valueStream().map(JsonNode::asString))
                .containsExactly(actions);
    }

    private void assertError(SseClient response, int status, String code) throws Exception {
        try (response) {
            assertThat(response.status()).isEqualTo(status);
            JsonNode body = json.readTree(response.body());
            assertThat(body.path("code").asString()).isEqualTo(code);
            assertThat(body.path("requestId").asString()).isNotBlank();
        }
    }

    private JsonNode data(SseClient.Event event) {
        assertThat(event).isNotNull();
        return json.readTree(event.data());
    }

    private SseClient open(String key, Long afterId, String lastEventId) throws Exception {
        String path = "/api/v1/incidents/" + key + "/events" + (afterId == null ? "" : "?afterId=" + afterId);
        return SseClient.open(http, uri(path), lastEventId);
    }

    private String createIncident() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/v1/incidents"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"systemKey\": \"shortlink-platform\", \"title\": \"统计积压\","
                                        + " \"impactSummary\": \"统计延迟\", \"affectedResourceKeys\": []}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).path("data").path("incidentKey").asString();
    }

    private int action(String key, String action, long expectedVersion) throws Exception {
        return http.send(
                        HttpRequest.newBuilder(uri("/api/v1/incidents/" + key + "/actions/" + action))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"expectedVersion\": " + expectedVersion + "}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    private JsonNode get(String path) throws Exception {
        return json.readTree(
                http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .body());
    }

    private long incidentId(String key) {
        return jdbc.queryForObject("SELECT id FROM incident WHERE incident_key = ?", Long.class, key);
    }

    private List<Long> timelineIds(String key) {
        return jdbc.queryForList(
                "SELECT t.id FROM incident_timeline_event t JOIN incident i ON i.id = t.incident_id"
                        + " WHERE i.incident_key = ? ORDER BY t.id",
                Long.class,
                key);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
