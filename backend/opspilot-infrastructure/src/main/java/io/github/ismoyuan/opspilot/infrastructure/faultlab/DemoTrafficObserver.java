package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.RedirectProbe;
import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment.StreamSnapshot;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient.DemoControlException;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient.StreamStatistics;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo 靶场的业务流量观察（S1、S3 共用）：直连 Redis 以 XINFO 读取统计 Stream（不读消息正文），以 HTTP 访问演示短链。
 *
 * <p>跳转探测不跟随重定向：返回 3xx 且 Location 不是 ShortLink 的 notfound 页面才算成功（ShortLink 的业务异常以 HTTP 200 的错误 JSON 返回，
 * 不能只看状态码是否为 5xx）。探测在响应头到达后即判定并关闭正文，整个交换受期限约束、到期取消（B34-R2）。请求带普通浏览器
 * User-Agent（ShortLink 按它解析访问统计），不带任何 Fault Lab 标记，避免控制信息进入靶场数据。
 */
final class DemoTrafficObserver {

    static final String NOT_FOUND_PATH = "/page/notfound";

    static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    /** 单次 Docker/Redis/Toxiproxy 调用的上限。 */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    /** 直连 Redis 与统计 Stream 的配置。 */
    record StreamTarget(
            String redisEndpoint, String redisUsername, String redisPassword, String streamKey, String consumerGroup) {}

    private final DemoControlClient control;
    private final StreamTarget stream;
    private final List<URI> probeUrls;
    private final Duration probeTimeout;
    private final HttpClient http;
    private final Clock clock;
    private final AtomicInteger nextProbe = new AtomicInteger();

    DemoTrafficObserver(
            DemoControlClient control, StreamTarget stream, List<URI> probeUrls, Duration probeTimeout, Clock clock) {
        this.control = control;
        this.stream = stream;
        this.probeUrls = List.copyOf(probeUrls);
        this.probeTimeout = probeTimeout;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(probeTimeout)
                .build();
    }

    StreamSnapshot readStream(Instant deadline) {
        Instant bounded = bounded(clock, deadline, CALL_TIMEOUT);
        StreamStatistics statistics = control(() -> control.streamStatistics(
                stream.redisEndpoint(),
                stream.redisUsername(),
                stream.redisPassword(),
                stream.streamKey(),
                stream.consumerGroup(),
                bounded));
        String id = statistics.lastGeneratedId();
        int dash = id == null ? -1 : id.indexOf('-');
        return new StreamSnapshot(
                statistics.observedAt(),
                id == null ? null : Long.parseLong(id.substring(0, dash)),
                id == null ? 0 : Long.parseLong(id.substring(dash + 1)),
                statistics.entriesAdded(),
                statistics.lag(),
                statistics.pending());
    }

    RedirectProbe probeRedirect(Instant deadline) {
        if (probeUrls.isEmpty()) {
            throw new FaultInjectionException("Preflight failed: no redirect probe URL is configured");
        }
        Duration timeout = Duration.between(clock.instant(), bounded(clock, deadline, probeTimeout));
        if (!timeout.isPositive()) {
            throw deadlineReached();
        }
        URI url = probeUrls.get(Math.floorMod(nextProbe.getAndIncrement(), probeUrls.size()));
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        long started = System.nanoTime();
        // 只需要状态码与 Location：响应头到达即完成（正文以流交付、随即关闭不读），整个交换最多等到期限，到期取消（B34-R2）。
        // 同步 send 配合 discarding 会等正文结束，HttpRequest.timeout 不约束正文读取
        CompletableFuture<HttpResponse<InputStream>> exchange =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        boolean succeeded;
        try {
            HttpResponse<InputStream> response = exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            response.body().close();
            succeeded = response.statusCode() >= 300
                    && response.statusCode() < 400
                    && response.headers()
                            .firstValue("Location")
                            .filter(location -> !location.contains(NOT_FOUND_PATH))
                            .isPresent();
        } catch (TimeoutException | ExecutionException | IOException ex) {
            exchange.cancel(true);
            succeeded = false;
        } catch (InterruptedException ex) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new FaultInjectionException("Fault lab action was interrupted");
        }
        return new RedirectProbe(succeeded, Duration.ofNanos(System.nanoTime() - started));
    }

    /** 调用期限：调用方给出的期限与单次调用上限中较早者；已经到期时立即失败，不发出请求。 */
    static Instant bounded(Clock clock, Instant deadline, Duration callLimit) {
        Instant now = clock.instant();
        if (!now.isBefore(deadline)) {
            throw deadlineReached();
        }
        Instant limit = now.plus(callLimit);
        return deadline.isBefore(limit) ? deadline : limit;
    }

    static FaultInjectionException deadlineReached() {
        return new FaultInjectionException("Demo environment: the fault lab deadline was reached");
    }

    interface Action<T> {
        T run();
    }

    /** 控制面失败转为注入器的脱敏说明（DemoControlClient 的说明本身固定且不含端点、凭据与容器 id）。 */
    static <T> T control(Action<T> action) {
        try {
            return action.run();
        } catch (DemoControlException ex) {
            throw new FaultInjectionException("Demo environment: " + ex.getMessage());
        }
    }
}
