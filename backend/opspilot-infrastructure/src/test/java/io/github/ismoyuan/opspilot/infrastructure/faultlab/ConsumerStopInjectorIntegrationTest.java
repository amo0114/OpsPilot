package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopSettings;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjection;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.application.faultlab.FaultTarget;
import io.github.ismoyuan.opspilot.application.faultlab.StatisticsConsumerStopInjector;
import io.github.ismoyuan.opspilot.infrastructure.config.ProviderProperties;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

/**
 * S3 注入器在真实 Docker Engine 与真实 Redis Stream 上的行为（08 TASK-093、09 §61～§65）：Engine API（unix socket）真正停止和启动一个
 * 正在消费的容器，Gate 读取真实 XINFO 统计。靶场缩成三个容器：Redis、持续 XADD 的生产者、以消费组读取并在之后 ACK 的消费者；跳转接口由
 * 本地 HTTP 服务返回 302。窗口按秒缩短，判定逻辑与 Demo 相同。完整 ShortLink 靶场的端到端运行记录在 PROGRESS（B34）。
 */
class ConsumerStopInjectorIntegrationTest {

    static final String STREAM = "short-link:stats-stream";
    static final String GROUP = "short-link:stats-stream:only-group";
    static final String CONSUMER_NAME = "opspilot-it-consumer-" + UUID.randomUUID();
    static final String DOCKER = "unix:///var/run/docker.sock";

    /** 以消费组读取、处理后 ACK（与 ShortLink 先处理后确认一致）；sh 作为 PID 1 不处理 SIGTERM，由 Docker 在宽限期后结束。 */
    static final String CONSUME_LOOP = "while true; do"
            + " ids=$(redis-cli -h redis --raw XREADGROUP GROUP '" + GROUP + "' c1 COUNT 100 BLOCK 500 STREAMS '"
            + STREAM + "' '>' | grep -E '^[0-9]+-[0-9]+$');"
            + " if [ -n \"$ids\" ]; then redis-cli -h redis XACK '" + STREAM + "' '" + GROUP + "' $ids > /dev/null; fi;"
            + " done";

    static final String PRODUCE_LOOP =
            "while true; do redis-cli -h redis XADD '" + STREAM + "' '*' statsRecord v > /dev/null; sleep 0.05; done";

    static final FaultTarget TARGET =
            new FaultTarget(11, "statistics-consumer-stop", "shortlink-platform", 3, "statistics-consumer");

    static Network network;
    static GenericContainer<?> redis;
    static GenericContainer<?> consumer;
    static GenericContainer<?> producer;
    static HttpServer shortLink;

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
        consumer = new GenericContainer<>("redis:7.4.5")
                .withNetwork(network)
                .withCreateContainerCmdModifier(cmd -> cmd.withName(CONSUMER_NAME))
                .withCommand("sh", "-c", CONSUME_LOOP);
        consumer.start();
        producer = new GenericContainer<>("redis:7.4.5").withNetwork(network).withCommand("sh", "-c", PRODUCE_LOOP);

        shortLink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 与 ShortLink 跳转一样：每次访问都真实写入一条统计消息
        shortLink.createContext("/", exchange -> {
            try {
                redis.execInContainer("redis-cli", "XADD", STREAM, "*", "statsRecord", "probe");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.getResponseHeaders().add("Location", "http://origin.invalid/item");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        shortLink.start();
    }

    @AfterAll
    static void stop() {
        if (shortLink != null) {
            shortLink.stop(0);
        }
        for (GenericContainer<?> container : new GenericContainer<?>[] {producer, consumer, redis}) {
            if (container != null) {
                container.stop();
            }
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void stopsTheRealConsumerConfirmsTheGateAndResets() throws Exception {
        StatisticsConsumerStopInjector injector = injector();

        // Preflight：没有负载（生产者未运行）时 Baseline 的写入速率为 0，不注入，消费者保持运行
        assertThatThrownBy(() -> injector.inject(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessage("Preflight failed: statistics messages are produced below the expected load rate");
        assertThat(state()).isEqualTo("running");

        producer.start();
        awaitLagAtMost(5, Duration.ofSeconds(20));

        Instant beforeInject = Instant.now();
        FaultInjection injection = injector.inject(TARGET);
        var inspected = DockerClientFactory.instance()
                .client()
                .inspectContainerCmd(CONSUMER_NAME)
                .exec();
        assertThat(inspected.getState().getStatus()).isEqualTo("exited");
        assertThat(injection.stoppedContainerId()).isEqualTo(inspected.getId());
        assertThat(injection.startedAt())
                .isEqualTo(Instant.parse(inspected.getState().getFinishedAt()))
                .isAfter(beforeInject);

        Instant detectedAt = injector.verifyInjected(TARGET);
        assertThat(detectedAt).isAfterOrEqualTo(injection.startedAt()).isBeforeOrEqualTo(Instant.now());
        // Gate：末次 lag ≥ 基线 lag + 20（基线 lag ≥ 0）
        assertThat(lag()).isGreaterThanOrEqualTo(20);
        assertThat(state()).isEqualTo("exited");

        injector.reset(TARGET);
        assertThat(state()).isEqualTo("running");
        // 消费者恢复后积压排空
        awaitLagAtMost(20, Duration.ofSeconds(30));
        // 再次 Reset：已在运行，只确认状态
        injector.reset(TARGET);
        assertThat(state()).isEqualTo("running");

        // B34-R1 P1：基线通过、停止消费者后外部负载退出，只剩跳转探测写入（每秒 2 条、lag 仍在上升）。旧判定会在约 10 秒满足 +20；
        // 扣除探测后写入速率不足，Gate 在期限内不确认，消费者保持停止，Reset 恢复
        awaitLagAtMost(5, Duration.ofSeconds(30));
        injector.inject(TARGET);
        producer.stop();
        long lagAfterStop = lag();
        assertThatThrownBy(() -> injector.verifyInjected(TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageStartingWith("Gate failed: not met within 15 seconds")
                .hasMessageContaining("statistics messages are produced below the expected load rate");
        assertThat(lag()).isGreaterThanOrEqualTo(lagAfterStop + 20);
        assertThat(state()).isEqualTo("exited");
        injector.reset(TARGET);
        assertThat(state()).isEqualTo("running");
    }

    /** 不属于绑定系统的目标：不检查、不停止、不启动任何容器。 */
    @Test
    void anUnboundSystemIsNotTouched() throws Exception {
        StatisticsConsumerStopInjector injector = injector();
        FaultTarget other = new FaultTarget(12, "statistics-consumer-stop", "test-platform", 9, "statistics-consumer");

        assertThatThrownBy(() -> injector.inject(other))
                .hasMessage("Fault lab control is not bound to the requested system");
        assertThatThrownBy(() -> injector.reset(other))
                .hasMessage("Fault lab control is not bound to the requested system");
        assertThat(state()).isEqualTo("running");
    }

    private StatisticsConsumerStopInjector injector() {
        FaultLabProperties.StatisticsConsumerStop config = new FaultLabProperties.StatisticsConsumerStop(
                true,
                "shortlink-platform",
                null,
                DOCKER,
                CONSUMER_NAME,
                "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379),
                null,
                null,
                STREAM,
                GROUP,
                List.of(URI.create("http://127.0.0.1:" + shortLink.getAddress().getPort() + "/abc")),
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
        ConsumerStopSettings settings = new ConsumerStopSettings(
                Duration.ofSeconds(3),
                Duration.ofMillis(500),
                1,
                2.0,
                20,
                20,
                Duration.ofSeconds(2),
                Duration.ofSeconds(15),
                20,
                0.01,
                4.0,
                Duration.ofMillis(500),
                Duration.ofSeconds(30));
        Clock clock = Clock.systemUTC();
        return new StatisticsConsumerStopInjector(
                new DemoConsumerStopEnvironment(
                        new DemoControlClient(clock, ProviderProperties.DEFAULT_MAX_RESPONSE_BYTES), config, clock),
                settings,
                config.systemKey(),
                config.targetResourceKeyOrDefault(),
                clock);
    }

    private static String state() {
        return DockerClientFactory.instance()
                .client()
                .inspectContainerCmd(CONSUMER_NAME)
                .exec()
                .getState()
                .getStatus();
    }

    private static long lag() throws Exception {
        List<String> lines = redis.execInContainer("redis-cli", "--raw", "XINFO", "GROUPS", STREAM)
                .getStdout()
                .lines()
                .toList();
        return Long.parseLong(lines.get(lines.indexOf("lag") + 1));
    }

    private static void awaitLagAtMost(long limit, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (lag() > limit) {
            assertThat(Instant.now()).as("lag did not drop to " + limit).isBefore(deadline);
            Thread.sleep(200);
        }
    }
}
