package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provider 的只读查询请求（06 §33～§35）：一次调用恰好发送一次、不跟随重定向、不重试；整个交换（连接、响应头与响应体）受调用的总期限约束，
 * 到期取消；响应体超过上限即停止读取。失败映射为 06 §35 错误码与固定文案，不含端点、查询、凭据或响应内容。
 *
 * <p>查询以表单编码的 POST 发送（Prometheus 与 Loki 的 query_range 均支持，语义仍是只读查询）：JDK HttpClient 会在连接于响应头之前断开时
 * 自动重发一次幂等请求（GET/HEAD，且没有按客户端关闭的开关），对 POST 不会这样做（除非设置 jdk.httpclient.enableAllMethodRetry，
 * 本系统不设置），由此保证“一次 Invocation 一次 Provider 请求”（B16-R1）。固定 HTTP/1.1，不做 h2c 升级。
 */
final class ProviderHttpClient {

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Clock clock;
    private final long maxResponseBytes;

    ProviderHttpClient(Clock clock, long maxResponseBytes) {
        this.clock = clock;
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * 受信端点须为不含用户信息、查询与片段的 http(s) 绝对地址（06 §19～§20：凭据不写在端点里）。
     *
     * @throws ProviderCallException INVALID_BINDING
     */
    static URI baseUri(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException ex) {
            throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Provider endpoint is not a valid URI");
        }
        boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        if (!http
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new ProviderCallException(
                    ErrorCode.INVALID_BINDING, "Provider endpoint must be an http(s) URL without credentials");
        }
        return uri;
    }

    /** 在基础地址后拼接固定 API 路径。 */
    static URI resolve(URI base, String path) {
        String prefix = base.toString();
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return URI.create(prefix + path);
    }

    /** application/x-www-form-urlencoded 编码（UTF-8）。 */
    static String form(Map<String, String> parameters) {
        StringJoiner body = new StringJoiner("&");
        parameters.forEach((name, value) -> body.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return body.toString();
    }

    /**
     * @param authorization Authorization 头的值；不需要认证时为空
     * @return 2xx 响应体
     * @throws ProviderCallException 连接失败、超时、非 2xx、响应过大
     */
    byte[] query(URI uri, Map<String, String> parameters, String authorization, Instant deadline) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (!remaining.isPositive()) {
            throw new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(remaining)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(parameters), StandardCharsets.UTF_8));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(builder.build(), info -> new BoundedBody(maxResponseBytes));
        HttpResponse<byte[]> response;
        try {
            response = pending.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
        } catch (ExecutionException ex) {
            throw translate(ex.getCause());
        }
        return checkStatus(response.statusCode(), response.body());
    }

    private static byte[] checkStatus(int status, byte[] body) {
        if (status >= 200 && status < 300) {
            return body;
        }
        String message = "Provider answered HTTP " + status;
        if (status == 401) {
            throw new ProviderCallException(ErrorCode.AUTHENTICATION_FAILED, message);
        }
        if (status == 403) {
            throw new ProviderCallException(ErrorCode.AUTHORIZATION_DENIED, message);
        }
        if (status == 400 || status == 422) {
            throw new ProviderCallException(ErrorCode.QUERY_REJECTED, message);
        }
        if (status == 429 || status >= 500) {
            throw new ProviderCallException(ErrorCode.PROVIDER_UNAVAILABLE, message);
        }
        throw new ProviderCallException(ErrorCode.PROVIDER_RESPONSE_INVALID, message);
    }

    private static ProviderCallException translate(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof ResponseTooLargeException) {
                return new ProviderCallException(
                        ErrorCode.RESULT_TOO_LARGE, "Provider response exceeded the size limit");
            }
            if (t instanceof HttpConnectTimeoutException || t instanceof HttpTimeoutException) {
                return new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
            }
            if (t instanceof ConnectException || t instanceof UnresolvedAddressException) {
                return new ProviderCallException(ErrorCode.CONNECTION_FAILED, "Provider could not be reached");
            }
        }
        return new ProviderCallException(ErrorCode.CONNECTION_FAILED, "Provider could not be reached");
    }

    private static final class ResponseTooLargeException extends RuntimeException {

        ResponseTooLargeException() {
            super("response too large", null, false, false);
        }
    }

    /** 累积响应体，超过上限即取消订阅并以 {@link ResponseTooLargeException} 结束。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final long limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        BoundedBody(long limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (buffer.size() + (long) item.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                buffer.writeBytes(bytes);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }
    }
}
