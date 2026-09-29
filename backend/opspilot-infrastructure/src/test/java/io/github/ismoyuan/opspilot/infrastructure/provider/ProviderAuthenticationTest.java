package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.ismoyuan.opspilot.application.secret.SecretNotFoundException;
import io.github.ismoyuan.opspilot.application.secret.SecretResolver;
import io.github.ismoyuan.opspilot.application.secret.SecretValue;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.binding.LokiResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusMetricBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.infrastructure.schema.SchemaCodecs;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B16-R1 凭据合同（06 §20、07 §63）：连接配置给出认证方式，凭据只经 credentialRef 由 SecretResolver 解析并只放入该次请求的
 * Authorization 头；配置与引用不一致时不猜测；凭据不出现在任何结果或错误文案中。
 */
class ProviderAuthenticationTest {

    static final String SECRET = "s3cr3t-token-value";

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    private final SecretResolver secrets = reference -> {
        if (reference.equals("env://OPSPILOT_PROVIDER_TOKEN")) {
            return new SecretValue(SECRET);
        }
        if (reference.equals("env://OPSPILOT_BROKEN_TOKEN")) {
            return new SecretValue("line1\r\nX-Injected: 1");
        }
        throw new SecretNotFoundException(SecretNotFoundException.Reason.NOT_FOUND, "Secret not found: " + reference);
    };

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = (exchange.getRequestURI().getPath().contains("loki")
                            ? "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}"
                            : "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void bearerAndBasicCredentialsAreSentOnlyInTheAuthorizationHeader() {
        ProviderOutcome bearer = prometheus(metrics("env://OPSPILOT_PROVIDER_TOKEN", "{\"authScheme\":\"BEARER\"}"));
        ProviderOutcome basic =
                loki(logs("env://OPSPILOT_PROVIDER_TOKEN", "{\"authScheme\":\"BASIC\",\"username\":\"opspilot\"}"));
        ProviderOutcome none = prometheus(metrics(null, "{}"));

        assertThat(bearer).isInstanceOf(ProviderOutcome.Fetched.class);
        assertThat(basic).isInstanceOf(ProviderOutcome.Fetched.class);
        assertThat(none).isInstanceOf(ProviderOutcome.Fetched.class);
        assertThat(authorizations)
                .containsExactly(
                        "Bearer " + SECRET,
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(("opspilot:" + SECRET).getBytes(StandardCharsets.UTF_8)),
                        "null");
        for (ProviderOutcome outcome : List.of(bearer, basic, none)) {
            assertThat(outcome.toString()).doesNotContain(SECRET);
        }
    }

    /** 配置与引用不一致、配置非法、Schema 不对、凭据不可用：均不发请求，文案不含凭据。 */
    @Test
    void inconsistentOrUnusableAuthenticationIsRejectedBeforeAnyRequest() {
        List<Map.Entry<ProviderOutcome, ProviderOutcome.Failed>> cases = List.of(
                Map.entry(
                        prometheus(metrics(null, "{\"authScheme\":\"BEARER\"}")),
                        failed(
                                ErrorCode.INVALID_BINDING,
                                "Connection authentication scheme and credential must be configured together")),
                Map.entry(
                        prometheus(metrics("env://OPSPILOT_PROVIDER_TOKEN", "{}")),
                        failed(
                                ErrorCode.INVALID_BINDING,
                                "Connection authentication scheme and credential must be configured together")),
                Map.entry(
                        prometheus(metrics("env://OPSPILOT_PROVIDER_TOKEN", "{\"authScheme\":\"DIGEST\"}")),
                        failed(ErrorCode.INVALID_BINDING, "Connection config is invalid")),
                Map.entry(
                        loki(logs("env://OPSPILOT_PROVIDER_TOKEN", "{\"authScheme\":\"BASIC\"}")),
                        failed(ErrorCode.INVALID_BINDING, "Connection config is invalid")),
                Map.entry(
                        prometheus(metrics("env://OPSPILOT_MISSING", "{\"authScheme\":\"BEARER\"}")),
                        failed(ErrorCode.SECRET_NOT_FOUND, "Connection credential is not available")),
                Map.entry(
                        prometheus(metrics("env://OPSPILOT_BROKEN_TOKEN", "{\"authScheme\":\"BEARER\"}")),
                        failed(ErrorCode.INVALID_BINDING, "Connection credential is not usable in a header")));
        cases.forEach(pair -> assertThat(pair.getKey()).isEqualTo(pair.getValue()));

        AdmittedInvocation wrongSchema = ProviderInvocations.admitted(
                CapabilityKey.LOGS_SEARCH,
                ProviderType.PROMETHEUS, // 连接配置 Schema 为 prometheus.connection.config，Loki Provider 不认
                endpoint(),
                null,
                "{}",
                new LokiResourceBindingV1(Map.of("app", "x")),
                new LogsSearchArgumentsV1(WindowKey.LAST_15_MIN, List.of(LogSeverity.ERROR), List.of()),
                window(),
                Duration.ofSeconds(5));
        assertThat(loki(wrongSchema))
                .isEqualTo(failed(ErrorCode.INVALID_BINDING, "Connection config schema is not supported"));
        assertThat(authorizations).isEmpty();
    }

    @Test
    void aRejectedCredentialIsAnAuthenticationFailureWithoutTheSecret() {
        status = 401;
        ProviderOutcome outcome = prometheus(metrics("env://OPSPILOT_PROVIDER_TOKEN", "{\"authScheme\":\"BEARER\"}"));

        assertThat(outcome).isEqualTo(failed(ErrorCode.AUTHENTICATION_FAILED, "Provider answered HTTP 401"));
        assertThat(outcome.toString()).doesNotContain(SECRET);
    }

    private ProviderOutcome prometheus(AdmittedInvocation invocation) {
        return ProviderInvocations.fetch(
                new PrometheusMetricsQueryProvider(
                        new ProviderHttpClient(Clock.systemUTC(), 1024 * 1024),
                        new ProviderAuthentication(SchemaCodecs.registry(), secrets),
                        new MetricSeriesSummarizer(new MetricsSettings(0.1)),
                        Clock.systemUTC(),
                        Duration.ofSeconds(15),
                        240),
                invocation);
    }

    private ProviderOutcome loki(AdmittedInvocation invocation) {
        return ProviderInvocations.fetch(
                new LokiLogsSearchProvider(
                        new ProviderHttpClient(Clock.systemUTC(), 1024 * 1024),
                        new ProviderAuthentication(SchemaCodecs.registry(), secrets),
                        new LogPatternAggregator(new Sanitizer(new SanitizerSettings(true)), LogsSettings.DEFAULTS),
                        Clock.systemUTC(),
                        500),
                invocation);
    }

    private AdmittedInvocation metrics(String credentialRef, String config) {
        return ProviderInvocations.admitted(
                CapabilityKey.METRICS_QUERY,
                ProviderType.PROMETHEUS,
                endpoint(),
                credentialRef,
                config,
                new PrometheusResourceBindingV1(
                        Map.of("job", "x"), Map.of("scrape.up", new PrometheusMetricBindingV1("up", "targets"))),
                new MetricsQueryArgumentsV1("scrape.up", WindowKey.LAST_15_MIN, false),
                window(),
                Duration.ofSeconds(5));
    }

    private AdmittedInvocation logs(String credentialRef, String config) {
        return ProviderInvocations.admitted(
                CapabilityKey.LOGS_SEARCH,
                ProviderType.LOKI,
                endpoint(),
                credentialRef,
                config,
                new LokiResourceBindingV1(Map.of("app", "x")),
                new LogsSearchArgumentsV1(WindowKey.LAST_15_MIN, List.of(LogSeverity.ERROR), List.of()),
                window(),
                Duration.ofSeconds(5));
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ResolvedWindow window() {
        Instant now = Instant.now();
        return new ResolvedWindow(new QueryWindow(now.minusSeconds(900), now), null);
    }

    private static ProviderOutcome.Failed failed(ErrorCode code, String message) {
        return new ProviderOutcome.Failed(code, message);
    }
}
