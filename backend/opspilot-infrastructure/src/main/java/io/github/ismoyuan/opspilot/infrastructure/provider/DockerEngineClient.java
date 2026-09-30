package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 经本机 unix socket 访问 Docker Engine API 的最小客户端（08 TASK-057、TASK-070）：只能发出两种请求——只读的
 * {@code GET /containers/{name}/json}，以及唯一的写操作 {@code POST /containers/{id}/restart}（只接受准入时解析出的容器 id）；没有创建、
 * 删除、exec 或其他路径，不经 CLI、shell 或子进程。一次调用一个连接（Connection: close）、不重试。unix socket 通道没有读超时，由看门狗
 * 在调用期限处关闭通道（记 TIMEOUT）；响应体（分块或定长）超过上限即停止（RESULT_TOO_LARGE）。请求开始写出之后的失败带
 * requestSent 标记：写操作此时结果未知，不能当作未发送（04 §82）。
 */
final class DockerEngineClient {

    /** 与 DockerResourceBindingV1 一致：只能是容器名，不会形成其他路径。 */
    private static final Pattern CONTAINER_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{1,127}");

    /** Docker 容器 id：64 位小写十六进制。 */
    static final Pattern CONTAINER_ID = Pattern.compile("[a-f0-9]{64}");

    private static final int MAX_HEADER_LINE = 8 * 1024;

    /** HTTP 状态与响应体。 */
    record Response(int status, byte[] body) {}

    private final Clock clock;
    private final long maxResponseBytes;

    DockerEngineClient(Clock clock, long maxResponseBytes) {
        this.clock = clock;
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * 受信端点须为 unix:///绝对路径（V0.1 只支持本机 Docker socket，不支持 TCP/TLS）。
     *
     * @throws ProviderCallException INVALID_BINDING
     */
    static Path socketPath(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException ex) {
            throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Docker endpoint is not a valid URI");
        }
        if (!"unix".equalsIgnoreCase(uri.getScheme())
                || uri.getRawAuthority() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPath() == null
                || !uri.getPath().startsWith("/")) {
            throw new ProviderCallException(
                    ErrorCode.INVALID_BINDING, "Docker endpoint must be unix:///path/to/docker.sock");
        }
        return Path.of(uri.getPath());
    }

    /** 读取一个容器的 inspect 结果。 */
    Response inspect(Path socket, String containerName, Instant deadline) {
        if (!CONTAINER_NAME.matcher(containerName).matches()) {
            throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Container name is invalid");
        }
        return exchange(
                socket,
                "GET /containers/" + containerName + "/json HTTP/1.1\r\n"
                        + "Host: docker\r\nAccept: application/json\r\nConnection: close\r\n\r\n",
                deadline);
    }

    /**
     * 重启一个容器（Docker 先停止、等待 {@code stopTimeoutSeconds} 后强制结束，再启动）。成功为 204。
     *
     * @param containerId 准入时解析出的完整容器 id，不接受名称
     */
    Response restart(Path socket, String containerId, int stopTimeoutSeconds, Instant deadline) {
        if (!CONTAINER_ID.matcher(containerId).matches()) {
            throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Container id is invalid");
        }
        if (stopTimeoutSeconds < 0) {
            throw new IllegalArgumentException("stopTimeoutSeconds must not be negative");
        }
        return exchange(
                socket,
                "POST /containers/" + containerId + "/restart?t=" + stopTimeoutSeconds + " HTTP/1.1\r\n"
                        + "Host: docker\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                deadline);
    }

    private Response exchange(Path socket, String request, Instant deadline) {
        if (!Duration.between(clock.instant(), deadline).isPositive()) {
            throw timeout();
        }
        SocketChannel channel;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        } catch (IOException ex) {
            throw unreachable();
        }
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(
                        Math.max(1, Duration.between(clock.instant(), deadline).toMillis()));
                channel.close();
            } catch (InterruptedException | IOException ignored) {
                // 调用已完成或通道已关闭
            }
        });
        boolean sending = false;
        try (channel) {
            try {
                channel.connect(UnixDomainSocketAddress.of(socket));
            } catch (AsynchronousCloseException ex) {
                throw timeout();
            } catch (IOException ex) {
                throw unreachable();
            }
            // 从此刻起远端可能已收到（部分）请求
            sending = true;
            ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            return read(Channels.newInputStream(channel));
        } catch (ProviderCallException ex) {
            throw sending && !ex.requestSent() ? new ProviderCallException(ex.code(), ex.getMessage(), true) : ex;
        } catch (AsynchronousCloseException ex) { // 看门狗到期关闭或线程被中断（ClosedByInterruptException）
            throw sending
                    ? new ProviderCallException(
                            ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout", true)
                    : timeout();
        } catch (IOException ex) {
            throw new ProviderCallException(
                    ErrorCode.CONNECTION_FAILED, "Provider connection was interrupted", sending);
        } finally {
            watchdog.interrupt();
        }
    }

    private Response read(InputStream in) throws IOException {
        String statusLine = line(in);
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
            throw invalid();
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
        long contentLength = -1;
        boolean chunked = false;
        for (String header = line(in); !header.isEmpty(); header = line(in)) {
            int colon = header.indexOf(':');
            if (colon <= 0) {
                throw invalid();
            }
            String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = header.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                try {
                    contentLength = Long.parseLong(value);
                } catch (NumberFormatException ex) {
                    throw invalid();
                }
            } else if (name.equals("transfer-encoding")
                    && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (chunked) {
            for (long size = chunkSize(line(in)); size > 0; size = chunkSize(line(in))) {
                copy(in, body, size);
                if (!line(in).isEmpty()) {
                    throw invalid();
                }
            }
        } else if (contentLength >= 0) {
            copy(in, body, contentLength);
        } else {
            copy(in, body, Long.MAX_VALUE);
        }
        return new Response(status, body.toByteArray());
    }

    private void copy(InputStream in, ByteArrayOutputStream out, long bytes) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = bytes;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                if (bytes == Long.MAX_VALUE) {
                    return;
                }
                throw invalid();
            }
            if (out.size() + (long) n > maxResponseBytes) {
                throw new ProviderCallException(
                        ErrorCode.RESULT_TOO_LARGE, "Provider response exceeded the size limit");
            }
            out.write(buffer, 0, n);
            remaining -= n;
        }
    }

    private static long chunkSize(String line) {
        int extension = line.indexOf(';');
        try {
            return Long.parseLong((extension < 0 ? line : line.substring(0, extension)).trim(), 16);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw invalid();
            }
            if (b == '\r') {
                if (in.read() != '\n') {
                    throw invalid();
                }
                return line.toString();
            }
            if (line.length() >= MAX_HEADER_LINE) {
                throw invalid();
            }
            line.append((char) b);
        }
    }

    private static ProviderCallException timeout() {
        return new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
    }

    private static ProviderCallException unreachable() {
        return new ProviderCallException(ErrorCode.CONNECTION_FAILED, "Provider could not be reached");
    }

    private static ProviderCallException invalid() {
        return new ProviderCallException(
                ErrorCode.PROVIDER_RESPONSE_INVALID, "Docker Engine response is not valid HTTP");
    }
}
