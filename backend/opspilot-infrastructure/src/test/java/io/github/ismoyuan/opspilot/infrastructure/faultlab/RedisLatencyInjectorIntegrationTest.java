package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.ismoyuan.opspilot.application.faultlab.FaultConfirmation;
import io.github.ismoyuan.opspilot.application.faultlab.FaultGroundTruthV1.SymptomBranch;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjection;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.application.faultlab.FaultTarget;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyInjector;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencySettings;
import io.github.ismoyuan.opspilot.infrastructure.config.ProviderProperties;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S1 注入器在真实 Toxiproxy 2.12.0 与真实 Redis 上的行为（08 TASK-094、09 §29～§33、§41）：控制 API 真正增删 downstream latency toxic，
 * 经同一代理的 PING 实测变慢，跳转症状来自经代理访问 Redis 的业务替身（每次跳转经代理 PING 两次，模拟 ShortLink 每次跳转多次访问
 * Redis）；统计 Stream 由持续 XADD 的生产者与消费组消费者构成。窗口按秒缩短，判定逻辑与 Demo 相同。完整 ShortLink 靶场的端到端运行
 * 记录在 PROGRESS（B35）。
 */
class RedisLatencyInjectorIntegrationTest {

    static final String STREAM = "short-link:stats-stream";
    static final String GROUP = "short-link:stats-stream:only-group";
    static final String PROXY = "shortlink-redis";

    static final String CONSUME_LOOP = "while true; do"
            + " ids=$(redis-cli -h redis --raw XREADGROUP GROUP '" + GROUP + "' c1 COUNT 100 BLOCK 500 STREAMS '"
            + STREAM + "' '>' | grep -E '^[0-9]+-[0-9]+$');"
            + " if [ -n \"$ids\" ]; then redis-cli -h redis XACK '" + STREAM + "' '" + GROUP + "' $ids > /dev/null; fi;"
            + " done";

    static final String PRODUCE_LOOP =
            "while true; do redis-cli -h redis XADD '" + STREAM + "' '*' statsRecord v > /dev/null; sleep 0.05; done";

    static final FaultTarget TARGET = new FaultTarget(31, "redis-latency", "shortlink-platform", 5, "shortlink-redis");

    static Network network;
    static GenericContainer<?> redis;
    static GenericContainer<?> toxiproxy;
    static GenericContainer<?> consumer;
    static GenericContainer<?> producer;
    static HttpServer shortLink;
    static ExecutorService handlers;
    /** 业务替身是否经代理访问 Redis。 */
    static volatile boolean redirectsUseRedis = true;

    static final HttpClient http = HttpClient.newHttpClient();
    static final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    static void start() throws Exception {
        network = Network.newNetwork();
        redis = new GenericContainer<>("redis:7.4.5")
                .withNetwork(network)
                .withNetworkAliases("redis")
                .withExposedPorts(6379);
        redis.start();
        assertThat(redis.execInContainer("redis-cli", "XGROUP", "CREATE", STREAM, GROUP, "$", "MKSTREAM")
                        .getStdout())
                .contains("OK");
        toxiproxy = new GenericContainer<>("ghcr.io/shopify/toxiproxy:2.12.0")
                .withNetwork(network)
                .withExposedPorts(8474, 6379);
        toxiproxy.start();
        HttpResponse<String> created = http.send(
                HttpRequest.newBuilder(api("/proxies"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + PROXY
                                + "\",\"listen\":\"0.0.0.0:6379\",\"upstream\":\"redis:6379\",\"enabled\":true}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        consumer = new GenericContainer<>("redis:7.4.5").withNetwork(network).withCommand("sh", "-c", CONSUME_LOOP);
        consumer.start();
        producer = new GenericContainer<>("redis:7.4.5").withNetwork(network).withCommand("sh", "-c", PRODUCE_LOOP);
        producer.start();

        shortLink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newCachedThreadPool();
        shortLink.setExecutor(handlers);
        shortLink.createContext("/", exchange -> {
            try (exchange) {
                if (redirectsUseRedis) {
                    pingThroughProxy();
                    pingThroughProxy();
                }
                exchange.getResponseHeaders().add("Location", "http://origin.invalid/item");
                exchange.sendResponseHeaders(302, -1);
            } catch (IOException ex) {
                // 客户端已取消
            }
        });
        shortLink.start();
    }

    @AfterAll
    static void stop() {
        if (shortLink != null) {
            shortLink.stop(0);
            handlers.shutdownNow();
        }
        for (GenericContainer<?> container : new GenericContainer<?>[] {producer, consumer, toxiproxy, redis}) {
            if (container != null) {
                container.stop();
            }
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void injectsRealLatencyConfirmsTheSymptomBranchAndResets() throws Exception {
        RedisLatencyInjector injector = injector(Duration.ofSeconds(30));

        // Preflight：代理上已有 toxic（例如上次未 Reset）时不注入、不动它
        addToxic("leftover", 1);
        assertThatThrownBy(() -> injector.inject(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessage("Preflight failed: the Redis proxy already has toxics");
        assertThat(toxicNames()).containsExactly("leftover");
        http.send(
                HttpRequest.newBuilder(api("/proxies/" + PROXY + "/toxics/leftover"))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        Instant before = Instant.now();
        FaultInjection injection = injector.inject(TARGET);
        assertThat(injection.startedAt()).isAfter(before);
        // ACC-S1-001：真实 toxic 存在且配置正确
        JsonNode toxic = toxics().get(0);
        assertThat(toxic.path("name").asString()).isEqualTo(RedisLatencyInjector.TOXIC_NAME);
        assertThat(toxic.path("type").asString()).isEqualTo("latency");
        assertThat(toxic.path("stream").asString()).isEqualTo("downstream");
        assertThat(toxic.path("toxicity").asDouble()).isEqualTo(1.0);
        assertThat(toxic.path("attributes").path("latency").asInt()).isEqualTo(600);
        assertThat(toxic.path("attributes").path("jitter").asInt()).isZero();

        FaultConfirmation confirmation = injector.verifyInjected(TARGET);
        var gate = confirmation.redisLatencyGate();
        assertThat(confirmation.detectedAt()).isAfterOrEqualTo(injection.startedAt());
        // ACC-S1-002／003：经代理 PING 实测达到 Gate；跳转每次两次往返，P99 达 LATENCY 分支
        assertThat(gate.pingMedianMs()).isGreaterThanOrEqualTo(500);
        assertThat(gate.symptomBranch()).isEqualTo(SymptomBranch.LATENCY);
        assertThat(gate.faultP99Ms()).isGreaterThanOrEqualTo(Math.max(gate.baselineP99Ms() * 4, 800));
        assertThat(gate.injectedLatencyMs()).isEqualTo(600);

        injector.reset(TARGET);
        assertThat(toxicNames()).isEmpty();
        assertThat(pingThroughProxy()).isLessThan(Duration.ofMillis(100));
        // 再次 Reset：没有 toxic，只确认恢复
        injector.reset(TARGET);
    }

    /** 09 §33：Redis 延迟生效但业务无影响属于 SETUP_FAILED，Gate 不确认；Reset 真实删除 toxic。 */
    @Test
    void slowRedisWithoutABusinessSymptomIsNotConfirmed() throws Exception {
        RedisLatencyInjector injector = injector(Duration.ofSeconds(12));
        try {
            injector.inject(TARGET);
            redirectsUseRedis = false;
            assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                    .isInstanceOf(FaultInjectionException.class)
                    .hasMessage(
                            "Gate failed: not met within 12 seconds (redirect symptoms are below both gate branches)");
            assertThat(toxicNames()).containsExactly(RedisLatencyInjector.TOXIC_NAME);
        } finally {
            redirectsUseRedis = true;
            injector.reset(TARGET);
        }
        assertThat(toxicNames()).isEmpty();
    }

    private RedisLatencyInjector injector(Duration gateTimeout) {
        String direct = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        String proxied = "redis://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(6379);
        FaultLabProperties.RedisLatency config = new FaultLabProperties.RedisLatency(
                true,
                "shortlink-platform",
                null,
                api(""),
                PROXY,
                proxied,
                direct,
                null,
                null,
                STREAM,
                GROUP,
                List.of(URI.create("http://127.0.0.1:" + shortLink.getAddress().getPort() + "/abc")),
                Duration.ofSeconds(3),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        RedisLatencySettings settings = new RedisLatencySettings(
                600,
                0,
                1.0,
                Duration.ofSeconds(3),
                Duration.ofMillis(500),
                1,
                2.0,
                20,
                20,
                0.01,
                5,
                Duration.ofMillis(100),
                Duration.ofMillis(300),
                Duration.ofMillis(500),
                3,
                4.0,
                Duration.ofMillis(800),
                0.05,
                0.05,
                gateTimeout,
                Duration.ofSeconds(60));
        Clock clock = Clock.systemUTC();
        return new RedisLatencyInjector(
                new DemoRedisLatencyEnvironment(
                        new DemoControlClient(clock, ProviderProperties.DEFAULT_MAX_RESPONSE_BYTES),
                        new ToxiproxyClient(config.toxiproxyEndpoint(), PROXY, clock),
                        config,
                        clock),
                settings,
                config.systemKey(),
                config.targetResourceKeyOrDefault(),
                clock);
    }

    private static URI api(String path) {
        return URI.create("http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8474) + path);
    }

    private static void addToxic(String name, int latencyMs) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(api("/proxies/" + PROXY + "/toxics"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name
                                + "\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":"
                                + latencyMs + "}}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static JsonNode toxics() throws Exception {
        return json.readTree(http.send(
                        HttpRequest.newBuilder(api("/proxies/" + PROXY + "/toxics"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body());
    }

    private static List<String> toxicNames() throws Exception {
        return toxics().valueStream().map(t -> t.path("name").asString()).toList();
    }

    /** 业务替身与测试用：经代理发一次 RESP PING，返回往返耗时。 */
    private static Duration pingThroughProxy() throws IOException {
        try (Socket socket = new Socket(toxiproxy.getHost(), toxiproxy.getMappedPort(6379))) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            long started = System.nanoTime();
            out.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] reply = new byte[7];
            int read = 0;
            while (read < reply.length) {
                int n = in.read(reply, read, reply.length - read);
                if (n < 0) {
                    throw new IOException("proxy closed the connection");
                }
                read += n;
            }
            if (!"+PONG\r\n".equals(new String(reply, StandardCharsets.US_ASCII))) {
                throw new IOException("unexpected PING reply");
            }
            return Duration.ofNanos(System.nanoTime() - started);
        }
    }
}
