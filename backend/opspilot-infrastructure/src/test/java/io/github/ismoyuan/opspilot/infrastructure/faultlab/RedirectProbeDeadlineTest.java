package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RedirectProbe;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopSettings;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.application.faultlab.StatisticsConsumerStopInjector;
import io.github.ismoyuan.opspilot.infrastructure.config.ProviderProperties;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B34-R2：跳转探测在真实 HTTP 上受期限约束。服务器及时返回 302＋Location 却不结束正文、或迟迟不返回响应头时，探测都在期限内返回，Gate
 * 不被拖过整体期限。
 */
class RedirectProbeDeadlineTest {

    /** 服务器挂住的时长，远大于各用例的期限。 */
    static final Duration HANG = Duration.ofMillis(1500);

    /** 期限之后允许的调度余量。 */
    static final Duration SLACK = Duration.ofMillis(300);

    /** FAST 的 Gate（400ms），Baseline 放宽到 300ms：真实 HTTP 首次连接较慢，40ms 窗口可能只有一次采样。 */
    static final ConsumerStopSettings SETTINGS = new ConsumerStopSettings(
            Duration.ofMillis(300),
            StatisticsConsumerStopInjectorTest.FAST.sampleInterval(),
            StatisticsConsumerStopInjectorTest.FAST.probesPerSample(),
            StatisticsConsumerStopInjectorTest.FAST.minLoadMessageRate(),
            StatisticsConsumerStopInjectorTest.FAST.healthyLag(),
            StatisticsConsumerStopInjectorTest.FAST.healthyPending(),
            StatisticsConsumerStopInjectorTest.FAST.stopGrace(),
            StatisticsConsumerStopInjectorTest.FAST.gateTimeout(),
            StatisticsConsumerStopInjectorTest.FAST.minLagGrowth(),
            StatisticsConsumerStopInjectorTest.FAST.maxErrorRate(),
            StatisticsConsumerStopInjectorTest.FAST.p99Factor(),
            StatisticsConsumerStopInjectorTest.FAST.p99Floor(),
            StatisticsConsumerStopInjectorTest.FAST.resetTimeout());

    enum Mode {
        NORMAL,
        HANG_BODY,
        SLOW_HEADERS
    }

    private volatile Mode mode = Mode.NORMAL;
    private HttpServer server;
    private ExecutorService handlers;
    private DemoConsumerStopEnvironment environment;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        // 挂住的处理器不能阻塞后续请求
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.start();
        environment = new DemoConsumerStopEnvironment(
                new DemoControlClient(Clock.systemUTC(), ProviderProperties.DEFAULT_MAX_RESPONSE_BYTES),
                config(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/abc")),
                Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    /** 302＋Location 及时到达、正文不结束：以响应头判定成功并关闭正文，不等正文。 */
    @Test
    void aHangingBodyAfterTheRedirectHeadersDoesNotHoldTheProbe() {
        assertThat(environment.probeRedirect(Instant.now().plusSeconds(2)).succeeded())
                .isTrue();
        mode = Mode.HANG_BODY;

        Instant started = Instant.now();
        RedirectProbe probe = environment.probeRedirect(started.plusMillis(300));

        assertThat(probe.succeeded()).isTrue();
        assertThat(Duration.between(started, Instant.now()))
                .isLessThan(Duration.ofMillis(300).plus(SLACK));
    }

    /** 响应头迟迟不到：期限到即取消交换，记为失败探测。 */
    @Test
    void slowHeadersFailTheProbeAtTheDeadline() {
        mode = Mode.SLOW_HEADERS;

        Instant started = Instant.now();
        RedirectProbe probe = environment.probeRedirect(started.plusMillis(300));

        assertThat(probe.succeeded()).isFalse();
        assertThat(Duration.between(started, Instant.now()))
                .isLessThan(Duration.ofMillis(300).plus(SLACK));
    }

    /** Gate（400ms）期间跳转响应头迟迟不到：Gate 在期限附近失败，不被拖到服务器结束。 */
    @Test
    void theGateFailsNearItsDeadlineWhenProbesHang() {
        StatisticsConsumerStopInjectorTest.Fake fake = new StatisticsConsumerStopInjectorTest.Fake();
        StatisticsConsumerStopInjector injector = injector(fake);
        injector.inject(StatisticsConsumerStopInjectorTest.TARGET);
        mode = Mode.SLOW_HEADERS;

        assertThatThrownBy(() -> injector.verifyInjected(StatisticsConsumerStopInjectorTest.TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessageStartingWith("Gate failed: not met within");
        Instant deadline = fake.finishedAt.plus(SETTINGS.gateTimeout());
        assertThat(Instant.now()).isBefore(deadline.plus(SLACK));
    }

    /** 正文挂住时探测按响应头成功，Gate 在期限内确认。 */
    @Test
    void theGateConfirmsWithinItsDeadlineWhenBodiesHang() {
        StatisticsConsumerStopInjectorTest.Fake fake = new StatisticsConsumerStopInjectorTest.Fake();
        StatisticsConsumerStopInjector injector = injector(fake);
        injector.inject(StatisticsConsumerStopInjectorTest.TARGET);
        mode = Mode.HANG_BODY;

        Instant detected = injector.verifyInjected(StatisticsConsumerStopInjectorTest.TARGET)
                .detectedAt();

        assertThat(detected).isBeforeOrEqualTo(fake.finishedAt.plus(SETTINGS.gateTimeout()));
    }

    /** Docker 与 Stream 用脚本化替身，跳转探测走真实 HTTP。 */
    private StatisticsConsumerStopInjector injector(StatisticsConsumerStopInjectorTest.Fake fake) {
        fake.probeWrites = false;
        ConsumerStopEnvironment composite = new ConsumerStopEnvironment() {
            @Override
            public ConsumerContainer inspectConsumer(Instant deadline) {
                return fake.inspectConsumer(deadline);
            }

            @Override
            public void stopConsumer(String containerId, Duration grace, Instant deadline) {
                fake.stopConsumer(containerId, grace, deadline);
            }

            @Override
            public void startConsumer(String containerId, Instant deadline) {
                fake.startConsumer(containerId, deadline);
            }

            @Override
            public StreamSnapshot readStream(Instant deadline) {
                return fake.readStream(deadline);
            }

            @Override
            public RedirectProbe probeRedirect(Instant deadline) {
                return environment.probeRedirect(deadline);
            }
        };
        return new StatisticsConsumerStopInjector(
                composite, SETTINGS, "shortlink-platform", "statistics-consumer", Clock.systemUTC());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Mode current = mode;
            if (current == Mode.SLOW_HEADERS) {
                pause();
            }
            exchange.getResponseHeaders().add("Location", "http://origin.invalid/item");
            if (current != Mode.HANG_BODY) {
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            // 分块正文：先写一个字节，正文迟迟不结束
            exchange.sendResponseHeaders(302, 0);
            OutputStream body = exchange.getResponseBody();
            body.write('x');
            body.flush();
            pause();
        } catch (IOException ignored) {
            // 客户端已关闭连接
        }
    }

    private static void pause() {
        try {
            Thread.sleep(HANG);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static FaultLabProperties.StatisticsConsumerStop config(URI probe) {
        return new FaultLabProperties.StatisticsConsumerStop(
                true,
                "shortlink-platform",
                null,
                "unix:///var/run/docker.sock",
                "unused",
                "redis://127.0.0.1:6379",
                null,
                null,
                "stream",
                "group",
                List.of(probe),
                Duration.ofSeconds(2),
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
    }
}
