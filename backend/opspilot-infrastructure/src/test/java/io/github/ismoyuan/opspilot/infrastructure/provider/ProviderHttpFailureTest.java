package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogsSearchArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.QueryWindow;
import io.github.ismoyuan.opspilot.application.capability.ResolvedWindow;
import io.github.ismoyuan.opspilot.application.capability.logs.LogPatternAggregator;
import io.github.ismoyuan.opspilot.application.capability.logs.LogsSettings;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricSeriesSummarizer;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricsSettings;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.binding.LokiResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusMetricBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.ResourceSelector;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 06 §33～§35：Provider 失败映射为统一错误码与固定文案（不含端点、查询或响应内容）；一次调用只发一次请求、不跟随重定向；
 * 整个交换受总期限约束（含慢响应体）；非法端点、非法响应与负值不产生结果。本地桩服务模拟 Provider 的异常行为。
 */
class ProviderHttpFailureTest {

    interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }

    private HttpServer server;
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
    private final AtomicReference<Handler> handler = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final ProviderHttpClient http = new ProviderHttpClient(Clock.systemUTC(), 1024 * 1024);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                handler.get().handle(exchange);
            } catch (Exception ex) {
                // 客户端取消后写入失败属预期
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void httpStatusesMapToProviderErrorTypes() {
        Map<Integer, ErrorCode> expected = new LinkedHashMap<>();
        expected.put(400, ErrorCode.QUERY_REJECTED);
        expected.put(422, ErrorCode.QUERY_REJECTED);
        expected.put(401, ErrorCode.AUTHENTICATION_FAILED);
        expected.put(403, ErrorCode.AUTHORIZATION_DENIED);
        expected.put(429, ErrorCode.PROVIDER_UNAVAILABLE);
        expected.put(503, ErrorCode.PROVIDER_UNAVAILABLE);
        expected.put(404, ErrorCode.PROVIDER_RESPONSE_INVALID);
        expected.put(302, ErrorCode.PROVIDER_RESPONSE_INVALID); // 不跟随重定向
        expected.forEach((status, code) -> {
            handler.set(exchange -> {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
                respond(exchange, status, "{\"error\":\"secret-detail\"}");
            });
            assertThatThrownBy(() ->
                            http.query(uri("/q"), Map.of(), null, Instant.now().plusSeconds(5)))
                    .as("HTTP %d", status)
                    .isInstanceOfSatisfying(ProviderCallException.class, ex -> {
                        assertThat(ex.code()).isEqualTo(code);
                        assertThat(ex.getMessage()).isEqualTo("Provider answered HTTP " + status);
                    });
        });
        assertThat(requests.get()).isEqualTo(expected.size()); // 每次调用恰好一次请求
    }

    /** 期限覆盖整个交换：响应头迟到与响应体迟到都在期限处超时，不无限等待。 */
    @Test
    void theDeadlineCoversHeadersAndBody() {
        handler.set(exchange -> {
            Thread.sleep(3_000);
            respond(exchange, 200, "{}");
        });
        assertTimeout();

        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(3_000);
                out.write("}".getBytes(StandardCharsets.UTF_8));
            }
        });
        assertTimeout();

        assertThatThrownBy(() ->
                        http.query(uri("/q"), Map.of(), null, Instant.now().minusMillis(1)))
                .isInstanceOfSatisfying(
                        ProviderCallException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TIMEOUT));
    }

    /**
     * B16-R1：服务端读完第一次请求后不给响应头就断开——一次查询只发送一次（不被 HTTP 客户端透明重发），记 CONNECTION_FAILED。
     * 原始套接字服务计数收到的请求。
     */
    @Test
    void aConnectionDroppedBeforeTheResponseIsNotRetried() throws Exception {
        AtomicInteger received = new AtomicInteger();
        try (ServerSocket raw = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                while (!raw.isClosed()) {
                    try (java.net.Socket socket = raw.accept()) {
                        java.io.InputStream in = socket.getInputStream();
                        StringBuilder head = new StringBuilder();
                        while (!head.toString().endsWith("\r\n\r\n")) {
                            int b = in.read();
                            if (b < 0) {
                                break;
                            }
                            head.append((char) b);
                        }
                        received.incrementAndGet();
                        if (received.get() > 1) {
                            socket.getOutputStream()
                                    .write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"
                                            .getBytes(StandardCharsets.UTF_8));
                        }
                        // 第一次：不写任何响应，直接关闭
                    } catch (IOException ex) {
                        return;
                    }
                }
            });
            URI uri = URI.create("http://127.0.0.1:" + raw.getLocalPort() + "/api/v1/query_range");

            assertThatThrownBy(() -> http.query(
                            uri, Map.of("query", "up"), null, Instant.now().plusSeconds(5)))
                    .isInstanceOfSatisfying(
                            ProviderCallException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONNECTION_FAILED));
            Thread.sleep(300); // 若有透明重发，此时已到达
            assertThat(received.get()).isEqualTo(1);
            acceptor.interrupt();
        }
    }

    @Test
    void anUnreachableProviderIsAConnectionFailure() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        assertThatThrownBy(() -> http.query(
                        URI.create("http://127.0.0.1:" + closed + "/q"),
                        Map.of(),
                        null,
                        Instant.now().plusSeconds(5)))
                .isInstanceOfSatisfying(ProviderCallException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.CONNECTION_FAILED);
                    assertThat(ex.getMessage()).doesNotContain(String.valueOf(closed));
                });
    }

    @Test
    void endpointsMustBePlainHttpUrlsWithoutCredentials() {
        for (String endpoint : new String[] {
            "ftp://prometheus:9090",
            "http://user:pw@prometheus:9090",
            "http://prometheus:9090?x=1",
            "prometheus:9090",
            "http:// bad"
        }) {
            assertThatThrownBy(() -> ProviderHttpClient.baseUri(endpoint))
                    .as(endpoint)
                    .isInstanceOfSatisfying(
                            ProviderCallException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_BINDING));
        }
        assertThat(ProviderHttpClient.resolve(
                        ProviderHttpClient.baseUri("http://prometheus:9090/prefix/"), "/api/v1/query_range"))
                .hasToString("http://prometheus:9090/prefix/api/v1/query_range");
        assertThat(ProviderHttpClient.form(Map.of("query", "sum(rate(x{a=\"b c\"}[1m])) & 1")))
                .isEqualTo("query=sum%28rate%28x%7Ba%3D%22b+c%22%7D%5B1m%5D%29%29+%26+1");
    }

    /** Prometheus 响应形状不对、状态不是 success、出现负值：均为 PROVIDER_RESPONSE_INVALID；绑定与凭据错配为 INVALID_BINDING。 */
    @Test
    void malformedPrometheusResponsesAndMisboundCallsProduceNoResult() {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("not json", "<html>");
        bodies.put("status error", "{\"status\":\"error\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}");
        bodies.put("vector", "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
        bodies.put(
                "bad pair",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"values\":[[1]]}]}}");
        bodies.put(
                "bad value",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"values\":[[1,\"x\"]]}]}}");
        for (Map.Entry<String, String> body : bodies.entrySet()) {
            handler.set(exchange -> respond(exchange, 200, body.getValue()));
            assertThat(ProviderInvocations.fetch(prometheus(), invocation(prometheusSelector(), null)))
                    .as(body.getKey())
                    .isEqualTo(new ProviderOutcome.Failed(
                            ErrorCode.PROVIDER_RESPONSE_INVALID,
                            "Prometheus response is not a valid range query result"));
        }
        handler.set(
                exchange -> respond(
                        exchange,
                        200,
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{},\"values\":[[1,\"-3\"]]}]}}"));
        assertThat(ProviderInvocations.fetch(prometheus(), invocation(prometheusSelector(), null)))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.PROVIDER_RESPONSE_INVALID, "Metric returned a negative value"));

        int before = requests.get();
        // 有凭据引用却没有认证方式：不猜测
        assertThat(ProviderInvocations.fetch(prometheus(), invocation(prometheusSelector(), "env://PROMETHEUS_TOKEN")))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.INVALID_BINDING,
                        "Connection authentication scheme and credential must be configured together"));
        assertThat(ProviderInvocations.fetch(
                        prometheus(), invocation(new LokiResourceBindingV1(Map.of("app", "x")), null)))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.INVALID_BINDING, "Resource binding is not a Prometheus binding"));
        assertThat(requests.get()).isEqualTo(before); // 配置错误不发请求
    }

    /** Loki 响应形状不对或时间戳非法为 PROVIDER_RESPONSE_INVALID；凭据与非 Loki 选择器为 INVALID_BINDING 且不发请求。 */
    @Test
    void malformedLokiResponsesAndMisboundCallsProduceNoResult() {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("not json", "oops");
        bodies.put("matrix", "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}");
        bodies.put(
                "numeric ts",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":"
                        + "[{\"stream\":{},\"values\":[[1,\"line\"]]}]}}");
        bodies.put(
                "bad ts",
                "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":"
                        + "[{\"stream\":{},\"values\":[[\"12x\",\"line\"]]}]}}");
        for (Map.Entry<String, String> body : bodies.entrySet()) {
            handler.set(exchange -> respond(exchange, 200, body.getValue()));
            assertThat(ProviderInvocations.fetch(
                            loki(), logsInvocation(new LokiResourceBindingV1(Map.of("app", "x")), null)))
                    .as(body.getKey())
                    .isEqualTo(new ProviderOutcome.Failed(
                            ErrorCode.PROVIDER_RESPONSE_INVALID, "Loki response is not a valid log query result"));
        }
        int before = requests.get();
        assertThat(ProviderInvocations.fetch(
                        loki(), logsInvocation(new LokiResourceBindingV1(Map.of("app", "x")), "env://LOKI_TOKEN")))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.INVALID_BINDING,
                        "Connection authentication scheme and credential must be configured together"));
        assertThat(ProviderInvocations.fetch(loki(), logsInvocation(prometheusSelector(), null)))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.INVALID_BINDING, "Resource binding is not a Loki binding"));
        assertThat(requests.get()).isEqualTo(before);
    }

    private LokiLogsSearchProvider loki() {
        return new LokiLogsSearchProvider(
                http,
                ProviderInvocations.noCredentials(),
                new LogPatternAggregator(new Sanitizer(new SanitizerSettings(true)), LogsSettings.DEFAULTS),
                Clock.systemUTC(),
                500);
    }

    private AdmittedInvocation logsInvocation(ResourceSelector selector, String credentialRef) {
        Instant now = Instant.now();
        return ProviderInvocations.admitted(
                CapabilityKey.LOGS_SEARCH,
                ProviderType.LOKI,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                credentialRef,
                selector,
                new LogsSearchArgumentsV1(WindowKey.LAST_15_MIN, List.of(LogSeverity.ERROR), List.of()),
                new ResolvedWindow(new QueryWindow(now.minusSeconds(900), now), null),
                Duration.ofSeconds(5));
    }

    private void assertTimeout() {
        long started = System.nanoTime();
        assertThatThrownBy(() ->
                        http.query(uri("/q"), Map.of(), null, Instant.now().plusMillis(400)))
                .isInstanceOfSatisfying(
                        ProviderCallException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    private PrometheusMetricsQueryProvider prometheus() {
        return new PrometheusMetricsQueryProvider(
                http,
                ProviderInvocations.noCredentials(),
                new MetricSeriesSummarizer(new MetricsSettings(0.1)),
                Clock.systemUTC(),
                Duration.ofSeconds(15),
                240);
    }

    private AdmittedInvocation invocation(ResourceSelector selector, String credentialRef) {
        Instant now = Instant.now();
        return ProviderInvocations.admitted(
                CapabilityKey.METRICS_QUERY,
                ProviderType.PROMETHEUS,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                credentialRef,
                selector,
                new MetricsQueryArgumentsV1("http.request.rate", WindowKey.LAST_15_MIN, false),
                new ResolvedWindow(new QueryWindow(now.minusSeconds(900), now), null),
                Duration.ofSeconds(5));
    }

    private static PrometheusResourceBindingV1 prometheusSelector() {
        return new PrometheusResourceBindingV1(
                Map.of("application", "shortlink-project"),
                Map.of("http.request.rate", new PrometheusMetricBindingV1("sum(rate(x[1m]))", "req/s")));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
