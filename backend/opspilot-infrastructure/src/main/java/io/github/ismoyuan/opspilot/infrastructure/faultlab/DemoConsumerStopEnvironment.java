package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.ConsumerStopEnvironment;
import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient;
import io.github.ismoyuan.opspilot.infrastructure.provider.DemoControlClient.ContainerState;
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
 * S3 Demo 靶场的真实访问（08 TASK-093）：Docker Engine API 控制消费者容器、Redis XINFO 读取统计 Stream、HTTP 访问演示短链。
 *
 * <p>跳转探测不跟随重定向：返回 3xx 且 Location 不是 ShortLink 的 notfound 页面才算成功（ShortLink 的业务异常以 HTTP 200 的错误 JSON 返回，
 * 不能只看状态码是否为 5xx）。探测在响应头到达后即判定并关闭正文，整个交换受期限约束、到期取消。请求带普通浏览器 User-Agent（ShortLink 按它解析访问统计），不带任何 Fault Lab 标记，避免控制信息进入靶场数据。
 */
final class DemoConsumerStopEnvironment implements ConsumerStopEnvironment {

    static final String NOT_FOUND_PATH = "/page/notfound";

    static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    /** 单次 Docker/Redis 调用的期限（停止另加宽限期）。 */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    private final DemoControlClient control;
    private final FaultLabProperties.StatisticsConsumerStop config;
    private final HttpClient http;
    private final Clock clock;
    private final AtomicInteger nextProbe = new AtomicInteger();

    DemoConsumerStopEnvironment(
            DemoControlClient control, FaultLabProperties.StatisticsConsumerStop config, Clock clock) {
        this.control = control;
        this.config = config;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(config.probeTimeoutOrDefault())
                .build();
    }

    @Override
    public ConsumerContainer inspectConsumer(Instant deadline) {
        Instant bounded = bounded(deadline, CALL_TIMEOUT);
        ContainerState state =
                control(() -> control.inspectContainer(config.dockerEndpoint(), config.containerName(), bounded));
        return new ConsumerContainer(
                state.containerId(),
                switch (state.runtimeState()) {
                    case RUNNING -> RuntimeState.RUNNING;
                    case STOPPED -> RuntimeState.STOPPED;
                    case RESTARTING -> RuntimeState.RESTARTING;
                    case PAUSED -> RuntimeState.PAUSED;
                    case UNKNOWN -> RuntimeState.UNKNOWN;
                },
                switch (state.health()) {
                    case HEALTHY -> HealthState.HEALTHY;
                    case STARTING -> HealthState.STARTING;
                    case NOT_CONFIGURED -> HealthState.NOT_CONFIGURED;
                    case UNHEALTHY, UNKNOWN -> HealthState.UNHEALTHY;
                },
                state.startedAt(),
                state.finishedAt());
    }

    @Override
    public void stopConsumer(String containerId, Duration grace, Instant deadline) {
        Instant bounded = bounded(deadline, CALL_TIMEOUT.plus(grace));
        control(() -> {
            control.stopContainer(config.dockerEndpoint(), containerId, Math.toIntExact(grace.toSeconds()), bounded);
            return null;
        });
    }

    @Override
    public void startConsumer(String containerId, Instant deadline) {
        Instant bounded = bounded(deadline, CALL_TIMEOUT);
        control(() -> {
            control.startContainer(config.dockerEndpoint(), containerId, bounded);
            return null;
        });
    }

    @Override
    public StreamSnapshot readStream(Instant deadline) {
        Instant bounded = bounded(deadline, CALL_TIMEOUT);
        StreamStatistics statistics = control(() -> control.streamStatistics(
                config.redisEndpoint(),
                config.redisUsername(),
                config.redisPassword(),
                config.streamKey(),
                config.consumerGroup(),
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

    @Override
    public RedirectProbe probeRedirect(Instant deadline) {
        List<URI> urls = config.redirectProbeUrls();
        if (urls.isEmpty()) {
            throw new FaultInjectionException("Preflight failed: no redirect probe URL is configured");
        }
        Duration timeout = Duration.between(clock.instant(), bounded(deadline, config.probeTimeoutOrDefault()));
        if (!timeout.isPositive()) {
            throw deadlineReached();
        }
        URI url = urls.get(Math.floorMod(nextProbe.getAndIncrement(), urls.size()));
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

    /** 调用期限：调用方给出的期限与本类单次调用上限中较早者；已经到期时立即失败，不发出请求。 */
    private Instant bounded(Instant deadline, Duration callLimit) {
        Instant now = clock.instant();
        if (!now.isBefore(deadline)) {
            throw deadlineReached();
        }
        Instant limit = now.plus(callLimit);
        return deadline.isBefore(limit) ? deadline : limit;
    }

    private static FaultInjectionException deadlineReached() {
        return new FaultInjectionException("Demo environment: the fault lab deadline was reached");
    }

    private interface Action<T> {
        T run();
    }

    /** 控制面失败转为注入器的脱敏说明（DemoControlClient 的说明本身固定且不含端点、凭据与容器 id）。 */
    private static <T> T control(Action<T> action) {
        try {
            return action.run();
        } catch (DemoControlException ex) {
            throw new FaultInjectionException("Demo environment: " + ex.getMessage());
        }
    }
}
