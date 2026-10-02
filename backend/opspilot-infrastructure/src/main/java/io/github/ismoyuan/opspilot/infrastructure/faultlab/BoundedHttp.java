package io.github.ismoyuan.opspilot.infrastructure.faultlab;

import io.github.ismoyuan.opspilot.application.faultlab.FaultInjectionException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fault Lab 控制面的 HTTP 请求：整个交换（含响应体）受期限约束，到期取消；失败为固定、脱敏的说明。 */
final class BoundedHttp {

    private BoundedHttp() {}

    static HttpResponse<byte[]> send(
            HttpClient http, HttpRequest.Builder request, Instant deadline, Clock clock, String peer) {
        Duration timeout = Duration.between(clock.instant(), deadline);
        if (!timeout.isPositive()) {
            throw DemoTrafficObserver.deadlineReached();
        }
        CompletableFuture<HttpResponse<byte[]>> exchange =
                http.sendAsync(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofByteArray());
        try {
            return exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            exchange.cancel(true);
            throw new FaultInjectionException("Demo environment: " + peer + " did not answer before the deadline");
        } catch (ExecutionException ex) {
            throw new FaultInjectionException("Demo environment: " + peer + " could not be reached");
        } catch (InterruptedException ex) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new FaultInjectionException("Fault lab action was interrupted");
        }
    }
}
