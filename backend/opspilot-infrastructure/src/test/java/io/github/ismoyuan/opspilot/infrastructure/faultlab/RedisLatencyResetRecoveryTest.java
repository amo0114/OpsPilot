package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RedirectProbe;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.StreamSnapshot;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyEnvironment;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencyInjector;
import io.github.ismoyuan.opspilot.application.faultlab.RedisLatencySettings;
import io.github.ismoyuan.opspilot.infrastructure.config.ProviderProperties;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient;
import java.io.IOException;
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
 * B35-R1：S1 Reset 在真实 HTTP 上确认跳转 P99 恢复（09 §41）。Toxiproxy、PING 与 Stream 用脚本化替身（toxic 删除后 PING 立即正常），跳转经
 * 正式 {@link DemoTrafficObserver} 访问本地真实 HTTP 服务：跳转仍成功（302）但每次要 1200ms 时 Reset 不完成并在期限失败；跳转变快后才完成。
 */
class RedisLatencyResetRecoveryTest {

    /** Reset 3 秒、健康跳转 P99 300ms；Baseline 放宽到 300ms（真实 HTTP 首次连接较慢）。 */
    static final RedisLatencySettings SETTINGS = new RedisLatencySettings(
            600,
            0,
            1.0,
            Duration.ofMillis(300),
            Duration.ofMillis(5),
            2,
            5.0,
            20,
            20,
            0.01,
            5,
            Duration.ofMillis(10),
            Duration.ofMillis(300),
            Duration.ofMillis(50),
            4,
            4.0,
            Duration.ofMillis(800),
            0.05,
            0.05,
            Duration.ofMillis(400),
            Duration.ofSeconds(3));

    private volatile Duration redirectDelay = Duration.ZERO;
    private HttpServer server;
    private ExecutorService handlers;
    private DemoTrafficObserver traffic;
    private final RedisLatencyInjectorTest.Fake fake = new RedisLatencyInjectorTest.Fake();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            try (exchange) {
                Thread.sleep(redirectDelay);
                exchange.getResponseHeaders().add("Location", "http://origin.invalid/item");
                exchange.sendResponseHeaders(302, -1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 客户端已取消
            }
        });
        server.start();
        traffic = new DemoTrafficObserver(
                new DemoControlClient(Clock.systemUTC(), ProviderProperties.DEFAULT_MAX_RESPONSE_BYTES),
                new DemoTrafficObserver.StreamTarget("redis://127.0.0.1:1", null, null, "stream", "group"),
                List.of(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/abc")),
                Duration.ofSeconds(2),
                Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    /** 跳转成功但仍慢：Reset 在期限失败（修复前只看成功率，会在约 2.5 秒后返回成功）。 */
    @Test
    void slowButSuccessfulRedirectsDoNotCompleteTheReset() {
        RedisLatencyInjector injector = injector();
        injector.inject(RedisLatencyInjectorTest.TARGET);
        redirectDelay = Duration.ofMillis(1200);

        Instant started = Instant.now();
        assertThatThrownBy(() -> injector.reset(RedisLatencyInjectorTest.TARGET))
                .isInstanceOf(FaultInjectionException.class)
                .hasMessage("Reset failed: the Redis round trip or redirects did not recover");
        assertThat(Duration.between(started, Instant.now())).isGreaterThanOrEqualTo(SETTINGS.resetTimeout());
        assertThat(fake.toxics).isEmpty();
    }

    /** 跳转先慢后恢复：Reset 等到跳转 P99 回到健康上限以内才完成。 */
    @Test
    void theResetCompletesOnlyAfterRedirectsAreFastAgain() throws Exception {
        RedisLatencyInjector injector = injector();
        injector.inject(RedisLatencyInjectorTest.TARGET);
        redirectDelay = Duration.ofMillis(1200);
        Thread recover = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(1500);
                redirectDelay = Duration.ZERO;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        Instant started = Instant.now();
        injector.reset(RedisLatencyInjectorTest.TARGET);

        assertThat(Duration.between(started, Instant.now())).isGreaterThanOrEqualTo(Duration.ofMillis(1500));
        recover.join();
    }

    private RedisLatencyInjector injector() {
        RedisLatencyEnvironment composite = new RedisLatencyEnvironment() {
            @Override
            public ProxyState inspectProxy(Instant deadline) {
                return fake.inspectProxy(deadline);
            }

            @Override
            public void addLatency(LatencyToxic toxic, Instant deadline) {
                fake.addLatency(toxic, deadline);
            }

            @Override
            public boolean removeLatency(String toxicName, Instant deadline) {
                return fake.removeLatency(toxicName, deadline);
            }

            @Override
            public List<Duration> pingThroughProxy(int count, Instant deadline) {
                return fake.pingThroughProxy(count, deadline);
            }

            @Override
            public StreamSnapshot readStream(Instant deadline) {
                return fake.readStream(deadline);
            }

            @Override
            public RedirectProbe probeRedirect(Instant deadline) {
                return traffic.probeRedirect(deadline);
            }
        };
        return new RedisLatencyInjector(
                composite, SETTINGS, "shortlink-platform", "shortlink-redis", Clock.systemUTC());
    }
}
