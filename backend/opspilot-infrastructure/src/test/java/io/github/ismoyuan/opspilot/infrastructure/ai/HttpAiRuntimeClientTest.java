package io.github.ismoyuan.opspilot.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ismoyuan.opspilot.application.ai.AiCallMetadata;
import io.github.ismoyuan.opspilot.application.ai.InvestigationStepDecision;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftRequest;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RemediationDraftResponse;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实 HTTP 上验证 AI Runtime 客户端（08 TASK-034、05 §89～§90、07 §86）：Token、correlationId、协议请求体；
 * 回显核对；各类失败映射；超时；每次调用恰好一次请求（无透明重试）；未配置 Token 时不发请求。桩为 JDK HttpServer。
 */
class HttpAiRuntimeClientTest {

    private static final Path FIXTURES = AiProtocolContractTest.CONTRACT.resolve("fixtures");
    private static final String TOKEN = "client-test-token";

    private final AiProtocolCodec codec = new AiProtocolCodec();
    private final JsonMapper plain = JsonMapper.builder().build();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;
    private volatile Function<Received, Reply> responder;
    private final Map<String, String> responseHeaders = new java.util.concurrent.ConcurrentHashMap<>();

    record Received(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst()
                    .map(e -> e.getValue().getFirst())
                    .orElse(null);
        }
    }

    /**
     * @param delayMillis 发送响应头之前的等待
     * @param bodyDelayMillis 已发送响应头和第一个字节之后、发送其余响应体之前的等待
     */
    record Reply(int status, String body, long delayMillis, long bodyDelayMillis) {
        static Reply of(int status, String body) {
            return new Reply(status, body, 0, 0);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        // 慢响应的处理线程不能挡住下一次请求的记录
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Received request = new Received(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        received.add(request);
        Reply reply = responder.apply(request);
        pause(reply.delayMillis());
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        responseHeaders.forEach(exchange.getResponseHeaders()::add);
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        try {
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes, 0, 1);
                exchange.getResponseBody().flush();
                pause(reply.bodyDelayMillis());
                exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
            }
        } catch (IOException ex) {
            // 客户端到期取消后连接已关闭
        }
        exchange.close();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void investigationStepSendsTokenCorrelationAndProtocolBodyAndReturnsTheDecision() {
        InvestigationStepRequest request = stepRequest();
        String answer = fixture("investigation-step-response/valid/request-queue-inspect.json");
        responder = r -> Reply.of(200, answer);

        InvestigationStepDecision decision = client(TOKEN).decideInvestigationStep(request, Duration.ofSeconds(5));

        assertThat(decision.response()).isInstanceOf(InvestigationStepResponse.RequestCapabilityStep.class);
        assertThat(decision.metadata()).isEqualTo(AiCallMetadata.UNKNOWN);
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.path()).isEqualTo("/internal/v1/investigation/step");
            assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(r.header("X-Correlation-Id")).isEqualTo("corr_step_42");
            assertThat(r.header("Content-Type")).isEqualTo("application/json");
            assertThat(plain.readTree(r.body())).isEqualTo(plain.readTree(codec.encode(request)));
        });
    }

    /** 调用元数据来自响应头（08 TASK-038）；缺失、超长或非数字的值不记录，但不否定已通过协议校验的结果。 */
    @Test
    void callMetadataIsReadFromHeadersAndBadValuesAreDropped() {
        String answer = fixture("investigation-step-response/valid/propose-hypothesis.json");
        responder = r -> Reply.of(200, answer);
        responseHeaders.putAll(Map.of(
                "X-OpsPilot-Model-Provider", "openai-compatible",
                "X-OpsPilot-Model-Name", "demo-model",
                "X-OpsPilot-Prompt-Template-Version", "investigation-v1",
                "X-OpsPilot-Prompt-Tokens", "1200",
                "X-OpsPilot-Completion-Tokens", "85"));

        assertThat(client(TOKEN)
                        .decideInvestigationStep(stepRequest(), Duration.ofSeconds(5))
                        .metadata())
                .isEqualTo(new AiCallMetadata("openai-compatible", "demo-model", "investigation-v1", 1200, 85));

        responseHeaders.put("X-OpsPilot-Model-Provider", "p".repeat(65));
        responseHeaders.put("X-OpsPilot-Prompt-Tokens", "-1");
        responseHeaders.put("X-OpsPilot-Completion-Tokens", "12abc");
        assertThat(client(TOKEN)
                        .decideInvestigationStep(stepRequest(), Duration.ofSeconds(5))
                        .metadata())
                .isEqualTo(new AiCallMetadata(null, "demo-model", "investigation-v1", null, null));
    }

    /** 回显只用于核对：runNo/stepId 与所发请求不符即非法输出（BND-015）。 */
    @Test
    void mismatchedEchoIsInvalidOutput() {
        String answer = fixture("investigation-step-response/valid/propose-hypothesis.json");
        for (String changed : List.of(
                answer.replace("\"runNo\": 2", "\"runNo\": 3"), answer.replace("\"stepId\": 42", "\"stepId\": 43"))) {
            responder = r -> Reply.of(200, changed);
            assertFailure(
                    () -> client(TOKEN).decideInvestigationStep(stepRequest(), Duration.ofSeconds(5)),
                    ErrorCode.AI_OUTPUT_INVALID);
        }
        RemediationDraftRequest draft = draftRequest();
        String proposal = fixture("remediation-draft-response/valid/propose-service-restart.json")
                .replace("corr_remediation_01", "corr_other");
        responder = r -> Reply.of(200, proposal);
        assertFailure(() -> client(TOKEN).draftRemediation(draft), ErrorCode.AI_OUTPUT_INVALID);
    }

    @Test
    void protocolViolatingBodyIsInvalidOutputWithoutEchoingIt() {
        String body = fixture("investigation-step-response/invalid/two-primary-payloads.json");
        responder = r -> Reply.of(200, body);

        assertThatThrownBy(() -> client(TOKEN).decideInvestigationStep(stepRequest(), Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(OpsPilotException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.AI_OUTPUT_INVALID);
                    assertThat(ex.getMessage()).doesNotContain("Statistics Consumer");
                });
        assertThat(received).hasSize(1);
    }

    /** 每种失败都只有一次请求：客户端不做透明重试（07 §86）。 */
    @ParameterizedTest
    @CsvSource({
        "502, AI_OUTPUT_INVALID",
        "504, AI_RUNTIME_TIMEOUT",
        "503, AI_RUNTIME_UNAVAILABLE",
        "500, AI_RUNTIME_UNAVAILABLE",
        "401, AI_RUNTIME_UNAVAILABLE",
        "403, AI_RUNTIME_UNAVAILABLE",
        "422, INTERNAL_ERROR",
        "404, INTERNAL_ERROR"
    })
    void errorStatusesMapWithoutRetry(int status, ErrorCode expected) {
        responder = r -> Reply.of(status, "{\"code\":\"X\",\"message\":\"secret-looking text\"}");

        assertThatThrownBy(() -> client(TOKEN).decideInvestigationStep(stepRequest(), Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(OpsPilotException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(expected);
                    assertThat(ex.details()).containsEntry("status", status);
                    assertThat(ex.getMessage()).doesNotContain("secret-looking");
                });
        assertThat(received).hasSize(1);
    }

    @Test
    void waitIsBoundedByTheCallersLimitAndNotRetried() {
        String answer = fixture("investigation-step-response/valid/propose-hypothesis.json");
        responder = r -> new Reply(200, answer, 2_000, 0);
        long started = System.nanoTime();

        assertFailure(
                () -> client(TOKEN).decideInvestigationStep(stepRequest(), Duration.ofMillis(300)),
                ErrorCode.AI_RUNTIME_TIMEOUT);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_500));
        assertThat(received).hasSize(1);
    }

    /** 上限覆盖完整响应：响应头与首字节立即到达、其余响应体延迟时同样按时失败（05 §89，B08-R1）。 */
    @Test
    void waitLimitCoversTheWholeResponseBody() {
        String answer = fixture("investigation-step-response/valid/propose-hypothesis.json");
        responder = r -> new Reply(200, answer, 0, 1_500);
        long started = System.nanoTime();

        assertFailure(
                () -> client(TOKEN).decideInvestigationStep(stepRequest(), Duration.ofMillis(300)),
                ErrorCode.AI_RUNTIME_TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_000));

        String proposal = fixture("remediation-draft-response/valid/propose-service-restart.json");
        responder = r -> new Reply(200, proposal, 0, 1_500);
        HttpAiRuntimeClient shortRemediation = new HttpAiRuntimeClient(
                new AiRuntimeProperties(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        TOKEN,
                        Duration.ofSeconds(1),
                        Duration.ofMillis(300)),
                codec);
        started = System.nanoTime();
        assertFailure(() -> shortRemediation.draftRemediation(draftRequest()), ErrorCode.AI_RUNTIME_TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_000));
        assertThat(received).hasSize(2);
    }

    @Test
    void unreachableRuntimeIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HttpAiRuntimeClient client =
                new HttpAiRuntimeClient(properties(URI.create("http://127.0.0.1:" + closedPort), TOKEN), codec);

        assertFailure(
                () -> client.decideInvestigationStep(stepRequest(), Duration.ofSeconds(2)),
                ErrorCode.AI_RUNTIME_UNAVAILABLE);
    }

    @Test
    void missingTokenFailsWithoutSendingAnything() {
        responder = r -> Reply.of(200, "{}");

        assertFailure(
                () -> client("").decideInvestigationStep(stepRequest(), Duration.ofSeconds(5)),
                ErrorCode.AI_RUNTIME_UNAVAILABLE);
        assertFailure(() -> client(" ").draftRemediation(draftRequest()), ErrorCode.AI_RUNTIME_UNAVAILABLE);
        assertThat(received).isEmpty();
    }

    @Test
    void remediationDraftRoundTripsThroughTheRuntime() {
        RemediationDraftRequest request = draftRequest();
        responder = r -> Reply.of(200, fixture("remediation-draft-response/valid/propose-service-restart.json"));

        RemediationDraftResponse response = client(TOKEN).draftRemediation(request);

        assertThat(response.proposal().action().targetResourceId()).isEqualTo(12);
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/internal/v1/remediation/draft");
            assertThat(r.header("X-Correlation-Id")).isEqualTo("corr_remediation_01");
        });
    }

    @Test
    void nonPositiveWaitIsRejectedBeforeSending() {
        assertThatThrownBy(() -> client(TOKEN).decideInvestigationStep(stepRequest(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(received).isEmpty();
    }

    private HttpAiRuntimeClient client(String token) {
        return new HttpAiRuntimeClient(
                properties(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), token), codec);
    }

    private static AiRuntimeProperties properties(URI baseUrl, String token) {
        return new AiRuntimeProperties(baseUrl, token, Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    private InvestigationStepRequest stepRequest() {
        return codec.decode(
                fixture("investigation-step-request/valid/full-context-all-descriptors.json"),
                InvestigationStepRequest.class);
    }

    private RemediationDraftRequest draftRequest() {
        return codec.decode(
                fixture("remediation-draft-request/valid/service-restart-allowed.json"), RemediationDraftRequest.class);
    }

    private static String fixture(String relative) {
        try {
            return Files.readString(FIXTURES.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void assertFailure(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        OpsPilotException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }
}
