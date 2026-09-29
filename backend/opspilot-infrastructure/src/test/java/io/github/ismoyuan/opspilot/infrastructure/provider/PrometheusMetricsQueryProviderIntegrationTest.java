package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.QueryWindow;
import io.github.ismoyuan.opspilot.application.capability.ResolvedWindow;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricSeriesSummarizer;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricsSettings;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusMetricBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * 08 TASK-052 “P99 真实落位”与 09 §7：真实 Prometheus（prom/prometheus:v3.5.0）每秒抓取本 JVM 内的真实 Micrometer 导出——与
 * Spring Boot 开启 {@code management.metrics.distribution.percentiles-histogram.http.server.requests=true} 相同的
 * http.server.requests 百分位直方图、JVM/进程指标与 HikariCP 连接池指标，公共标签 application=shortlink-project——再用 Demo Seed 中
 * 的原样模板经 Provider 查询。验证标签筛选、窗口、秒→毫秒、等长紧邻比较、空序列、NaN、多序列与被拒查询。
 *
 * <p>ShortLink 靶场本身的导出配置与数据在 TASK-105/106 校准，本测试不代替。
 */
class PrometheusMetricsQueryProviderIntegrationTest {

    static final String SEED = "src/main/resources/db/demo/R__shortlink_demo_seed.sql";
    static final Duration FAST = Duration.ofMillis(20);
    static final Duration SLOW = Duration.ofMillis(1200);
    static final Duration PHASE = Duration.ofSeconds(15);

    static final PrometheusMeterRegistry REGISTRY = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    static final HttpServer EXPORTER = startExporter();
    static GenericContainer<?> prometheus;
    static HikariDataSource pool;
    static ScheduledExecutorService load;
    static final AtomicReference<Duration> LATENCY = new AtomicReference<>(FAST);
    static Instant slowStart;
    static Instant slowEnd;
    static Map<String, PrometheusMetricBindingV1> seedMetrics;

    @BeforeAll
    static void scrapeRealMicrometerExport() throws Exception {
        seedMetrics = seedTemplates();
        REGISTRY.config().commonTags("application", "shortlink-project");
        new JvmMemoryMetrics().bindTo(REGISTRY);
        new ProcessorMetrics().bindTo(REGISTRY);
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:mysql://127.0.0.1:1/none");
        hikari.setPoolName("shortlink");
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(0);
        hikari.setInitializationFailTimeout(-1);
        hikari.setMetricRegistry(REGISTRY);
        pool = new HikariDataSource(hikari);
        Timer ok = requests("200", "SUCCESS");
        Timer failed = requests("500", "SERVER_ERROR");
        // 没有任何请求的直方图：分位数为 NaN
        Timer.builder("idle.requests").publishPercentileHistogram().register(REGISTRY);

        Testcontainers.exposeHostPorts(EXPORTER.getAddress().getPort());
        prometheus = new GenericContainer<>("prom/prometheus:v3.5.0")
                .withExposedPorts(9090)
                .withCopyToContainer(
                        Transferable.of("""
                                global:
                                  scrape_interval: 1s
                                scrape_configs:
                                  - job_name: shortlink
                                    metrics_path: /metrics
                                    static_configs:
                                      - targets: ['host.testcontainers.internal:%d']
                                """.formatted(EXPORTER.getAddress().getPort())),
                        "/etc/prometheus/prometheus.yml")
                .waitingFor(Wait.forHttp("/-/ready").forPort(9090));
        prometheus.start();

        load = Executors.newSingleThreadScheduledExecutor();
        load.scheduleAtFixedRate(
                () -> {
                    for (int i = 0; i < 19; i++) {
                        ok.record(LATENCY.get());
                    }
                    failed.record(LATENCY.get());
                },
                0,
                100,
                TimeUnit.MILLISECONDS);
        Thread.sleep(PHASE.toMillis());
        LATENCY.set(SLOW);
        slowStart = Instant.now();
        Thread.sleep(PHASE.toMillis());
        slowEnd = Instant.now();
        Thread.sleep(3_000); // 最后一次抓取入库
    }

    @AfterAll
    static void stop() {
        if (load != null) {
            load.shutdownNow();
        }
        if (prometheus != null) {
            prometheus.stop();
        }
        if (pool != null) {
            pool.close();
        }
        EXPORTER.stop(0);
    }

    /** Seed 的 P99 模板：慢阶段约为 1.2 秒对应的直方图桶内插值（毫秒），前一等长紧邻窗口（快阶段）为几十毫秒；比较有真实依据。 */
    @Test
    void theSeedP99TemplateMeasuresMillisecondsAndComparesAdjacentWindows() {
        // 当前窗口从切换到慢请求的时刻开始，前一窗口是紧邻的等长快请求阶段（点对齐在 start + k·step 上）
        Duration length = Duration.between(slowStart, slowEnd).minusSeconds(2);
        ResolvedWindow window = new ResolvedWindow(
                new QueryWindow(slowStart, slowStart.plus(length)),
                new QueryWindow(slowStart.minus(length), slowStart));

        MetricsQueryResultV1 result = fetched("http.request.latency.p99", seedMetrics, window);

        assertThat(result.unit()).isEqualTo("ms");
        assertThat(result.sampleCount()).isGreaterThan(5);
        // Micrometer 默认直方图在 1.2s 附近的桶为 (1073.74ms, 1431.66ms]；秒→毫秒换算已由模板完成
        assertThat(result.latest()).isBetween(1000.0, 1500.0);
        assertThat(result.average()).isBetween(1000.0, 1500.0);
        assertThat(result.previousWindow().sampleCount()).isGreaterThan(5);
        assertThat(result.previousWindow().average()).isBetween(10.0, 100.0);
        assertThat(result.changePercent()).isGreaterThan(900.0);
    }

    /** Seed 的其余 7 个模板对真实导出各得到一条序列、单位语义正确（比率在 [0,1]、连接池上限 10）。 */
    @Test
    void everySeedTemplateReturnsOneSeriesFromTheRealExport() {
        ResolvedWindow window =
                new ResolvedWindow(new QueryWindow(slowEnd.minus(Duration.ofSeconds(20)), slowEnd), null);
        Map<String, MetricsQueryResultV1> results = new HashMap<>();
        for (String key : seedMetrics.keySet()) {
            MetricsQueryResultV1 result = fetched(key, seedMetrics, window);
            assertThat(result.sampleCount()).as(key).isGreaterThan(5);
            results.put(key, result);
        }
        assertThat(results.get("http.request.error_rate").average()).isBetween(0.04, 0.06); // 20 次中 1 次 500
        // 实际约 200 次/秒；序列不足 1 分钟时 rate(...[1m]) 由小到大逐步接近（Prometheus 对新序列的外推），故只断言量级
        assertThat(results.get("http.request.rate").latest()).isBetween(50.0, 250.0);
        assertThat(results.get("jvm.cpu.usage").max()).isBetween(0.0, 1.0);
        assertThat(results.get("jvm.memory.heap.usage").average()).isBetween(0.0, 1.0);
        assertThat(results.get("db.pool.max").latest()).isEqualTo(10.0);
        assertThat(results.get("db.pool.active").latest()).isZero();
        assertThat(results.get("db.pool.pending").latest()).isZero();
    }

    /** 标签不匹配得到空序列、无流量的分位数为 NaN：都没有有效样本，不当 0；前一窗口在导出开始之前，同样不可比较。 */
    @Test
    void emptySeriesNanPointsAndMissingHistoryAreNotZero() {
        Map<String, PrometheusMetricBindingV1> metrics = Map.of(
                "absent.rate",
                new PrometheusMetricBindingV1(
                        "sum(rate(http_server_requests_seconds_count{application=\"not-this-app\"}[1m]))", "req/s"),
                "idle.latency.p99",
                new PrometheusMetricBindingV1(
                        "histogram_quantile(0.99, sum by (le) (rate(idle_requests_seconds_bucket{application=\"shortlink-project\"}[1m]))) * 1000",
                        "ms"));
        QueryWindow current = new QueryWindow(slowEnd.minus(Duration.ofMinutes(5)), slowEnd);
        ResolvedWindow window =
                new ResolvedWindow(current, new QueryWindow(current.start().minus(current.length()), current.start()));

        MetricsQueryResultV1 absent = fetched("absent.rate", metrics, window);
        assertThat(absent.sampleCount()).isZero();
        assertThat(absent.average()).isNull();
        assertThat(absent.previousWindow().average()).isNull();
        assertThat(absent.changePercent()).isNull();

        ProviderOutcome.Fetched idle = (ProviderOutcome.Fetched) ProviderInvocations.fetch(
                provider(), invocation("idle.latency.p99", metrics, window, Duration.ofSeconds(10)));
        assertThat(((MetricsQueryResultV1) idle.result()).sampleCount()).isZero();
        assertThat(idle.rawResult()).contains("current ").contains("NaN");
    }

    @Test
    void misconfiguredTemplatesAreReportedAsBindingOrQueryErrors() {
        ResolvedWindow window = new ResolvedWindow(new QueryWindow(slowEnd.minusSeconds(20), slowEnd), null);
        Map<String, PrometheusMetricBindingV1> metrics = Map.of(
                "many.series",
                new PrometheusMetricBindingV1("http_server_requests_seconds_count", "count"),
                "broken",
                new PrometheusMetricBindingV1("sum(", "count"));

        assertThat(ProviderInvocations.fetch(
                        provider(), invocation("many.series", metrics, window, Duration.ofSeconds(10))))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.INVALID_BINDING, "Metric template must return at most one series"));
        assertThat(ProviderInvocations.fetch(provider(), invocation("broken", metrics, window, Duration.ofSeconds(10))))
                .isEqualTo(new ProviderOutcome.Failed(ErrorCode.QUERY_REJECTED, "Provider answered HTTP 400"));
    }

    @Test
    void anOversizedResponseIsNotRead() {
        PrometheusMetricsQueryProvider tiny = new PrometheusMetricsQueryProvider(
                new ProviderHttpClient(Clock.systemUTC(), 64),
                ProviderInvocations.noCredentials(),
                new MetricSeriesSummarizer(new MetricsSettings(0.1)),
                Clock.systemUTC(),
                Duration.ofSeconds(1),
                240);
        ResolvedWindow window = new ResolvedWindow(new QueryWindow(slowEnd.minusSeconds(20), slowEnd), null);

        assertThat(ProviderInvocations.fetch(
                        tiny, invocation("http.request.rate", seedMetrics, window, Duration.ofSeconds(10))))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.RESULT_TOO_LARGE, "Provider response exceeded the size limit"));
    }

    /**
     * B16-R1：真实 Prometheus 开启 basic_auth（web.config.file，bcrypt 散列）——BASIC 配置经 SecretResolver 解析的凭据可查询；
     * 错误凭据被真实服务以 401 拒绝为 AUTHENTICATION_FAILED，文案不含凭据。
     */
    @Test
    void basicAuthenticationAgainstARealPrometheus() {
        try (GenericContainer<?> secured = new GenericContainer<>("prom/prometheus:v3.5.0")
                .withExposedPorts(9090)
                .withCopyToContainer(Transferable.of("""
                                basic_auth_users:
                                  opspilot: $2b$10$JaErsoUAF4pc3G8A/zWdneuIKeqQ.kn0SK3gE6FEsDtaPw/f5m9ri
                                """), "/etc/prometheus/web.yml")
                .withCommand(
                        "--config.file=/etc/prometheus/prometheus.yml", "--web.config.file=/etc/prometheus/web.yml")
                .waitingFor(Wait.forHttp("/-/ready")
                        .forPort(9090)
                        .withBasicCredentials("opspilot", "opspilot-test-pass"))) {
            secured.start();
            String endpoint = "http://" + secured.getHost() + ":" + secured.getMappedPort(9090);
            ResolvedWindow window =
                    new ResolvedWindow(new QueryWindow(Instant.now().minusSeconds(60), Instant.now()), null);
            Map<String, PrometheusMetricBindingV1> metrics =
                    Map.of("scrape.up", new PrometheusMetricBindingV1("sum(up)", "targets"));

            ProviderOutcome accepted = ProviderInvocations.fetch(
                    authenticated("opspilot-test-pass"), authenticatedCall(endpoint, metrics, window));
            ProviderOutcome rejected = ProviderInvocations.fetch(
                    authenticated("wrong-password"), authenticatedCall(endpoint, metrics, window));

            assertThat(accepted).isInstanceOf(ProviderOutcome.Fetched.class);
            assertThat(rejected)
                    .isEqualTo(
                            new ProviderOutcome.Failed(ErrorCode.AUTHENTICATION_FAILED, "Provider answered HTTP 401"));
            assertThat(rejected.toString()).doesNotContain("wrong-password");
        }
    }

    private static PrometheusMetricsQueryProvider authenticated(String password) {
        return new PrometheusMetricsQueryProvider(
                new ProviderHttpClient(Clock.systemUTC(), 8L * 1024 * 1024),
                new ProviderAuthentication(
                        io.github.ismoyuan.opspilot.infrastructure.schema.SchemaCodecs.registry(),
                        reference -> new io.github.ismoyuan.opspilot.application.secret.SecretValue(password)),
                new MetricSeriesSummarizer(new MetricsSettings(0.1)),
                Clock.systemUTC(),
                Duration.ofSeconds(1),
                240);
    }

    private static AdmittedInvocation authenticatedCall(
            String endpoint, Map<String, PrometheusMetricBindingV1> metrics, ResolvedWindow window) {
        return ProviderInvocations.admitted(
                CapabilityKey.METRICS_QUERY,
                ProviderType.PROMETHEUS,
                endpoint,
                "env://OPSPILOT_PROMETHEUS_PASSWORD",
                "{\"authScheme\":\"BASIC\",\"username\":\"opspilot\"}",
                new PrometheusResourceBindingV1(Map.of("job", "prometheus"), metrics),
                new MetricsQueryArgumentsV1("scrape.up", WindowKey.LAST_15_MIN, false),
                window,
                Duration.ofSeconds(10));
    }

    // ---------------------------------------------------------------- helpers

    private static MetricsQueryResultV1 fetched(
            String metricKey, Map<String, PrometheusMetricBindingV1> metrics, ResolvedWindow window) {
        ProviderOutcome outcome =
                ProviderInvocations.fetch(provider(), invocation(metricKey, metrics, window, Duration.ofSeconds(10)));
        assertThat(outcome).as(metricKey).isInstanceOf(ProviderOutcome.Fetched.class);
        return (MetricsQueryResultV1) ((ProviderOutcome.Fetched) outcome).result();
    }

    static PrometheusMetricsQueryProvider provider() {
        return new PrometheusMetricsQueryProvider(
                new ProviderHttpClient(Clock.systemUTC(), 8L * 1024 * 1024),
                ProviderInvocations.noCredentials(),
                new MetricSeriesSummarizer(new MetricsSettings(0.1)),
                Clock.systemUTC(),
                Duration.ofSeconds(1),
                240);
    }

    static AdmittedInvocation invocation(
            String metricKey, Map<String, PrometheusMetricBindingV1> metrics, ResolvedWindow window, Duration timeout) {
        return ProviderInvocations.admitted(
                CapabilityKey.METRICS_QUERY,
                ProviderType.PROMETHEUS,
                "http://" + prometheus.getHost() + ":" + prometheus.getMappedPort(9090),
                null,
                new PrometheusResourceBindingV1(Map.of("application", "shortlink-project"), metrics),
                new MetricsQueryArgumentsV1(metricKey, WindowKey.LAST_15_MIN, window.previous() != null),
                window,
                timeout);
    }

    private static Timer requests(String status, String outcome) {
        return Timer.builder("http.server.requests")
                .tags("method", "GET", "uri", "/{shortUri}", "status", status, "outcome", outcome, "exception", "none")
                .publishPercentileHistogram()
                .register(REGISTRY);
    }

    private static HttpServer startExporter() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            server.createContext("/metrics", exchange -> {
                byte[] body = REGISTRY.scrape().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 从 Demo Seed 原样读取 redirect-service 的指标模板与单位。 */
    private static Map<String, PrometheusMetricBindingV1> seedTemplates() throws IOException {
        String seed = Files.readString(Path.of(SEED));
        Matcher matcher = Pattern.compile(
                        "'([a-z][a-z0-9_.]*)', JSON_OBJECT\\(\\s*'queryTemplate', '([^']*)',\\s*'unit', '([^']*)'\\)")
                .matcher(seed);
        Map<String, PrometheusMetricBindingV1> metrics = new HashMap<>();
        while (matcher.find()) {
            metrics.put(matcher.group(1), new PrometheusMetricBindingV1(matcher.group(2), matcher.group(3)));
        }
        assertThat(metrics).hasSize(8);
        return Map.copyOf(metrics);
    }
}
