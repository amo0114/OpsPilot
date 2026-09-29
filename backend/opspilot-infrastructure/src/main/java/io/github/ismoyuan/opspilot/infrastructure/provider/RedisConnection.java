package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小 RESP2 客户端（08 TASK-054/056）：一个套接字、同步请求/应答，只能发送 {@link RedisCommand} 中的命令；不做连接池、重连、重试或
 * 管道——一次调用的每个命令恰好发送一次。每次读写前以调用期限设置套接字超时；应答累计字节超过上限即停止（RESULT_TOO_LARGE），
 * 嵌套深度受限。错误应答以 {@link RedisErrorReply} 抛出，由 Provider 按语境映射；错误文本不进入文案。
 */
final class RedisConnection implements AutoCloseable {

    static final int DEFAULT_PORT = 6379;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_LINE = 64 * 1024;

    /**
     * Redis 返回的错误应答；code 为首个词（如 WRONGPASS、NOPERM、ERR）。原文只用于分类，不进入异常消息、日志或文案（可能含键名）。
     */
    static final class RedisErrorReply extends RuntimeException {

        private final String code;
        private final String reply;

        RedisErrorReply(String reply) {
            super("redis error reply", null, false, false);
            int space = reply.indexOf(' ');
            this.code = space < 0 ? reply : reply.substring(0, space);
            this.reply = reply;
        }

        String code() {
            return code;
        }

        boolean mentions(String text) {
            return reply.contains(text);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Clock clock;
    private final long maxResponseBytes;
    private long bytesRead;

    private RedisConnection(Socket socket, Clock clock, long maxResponseBytes) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
        this.clock = clock;
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * 受信端点须为 redis://host[:port]，不含用户信息、库号、查询或片段（凭据只经 credentialRef）。
     *
     * @throws ProviderCallException INVALID_BINDING
     */
    static InetSocketAddress address(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException ex) {
            throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Redis endpoint is not a valid URI");
        }
        String path = uri.getRawPath();
        if (!"redis".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || (path != null && !path.isEmpty() && !path.equals("/"))) {
            throw new ProviderCallException(
                    ErrorCode.INVALID_BINDING, "Redis endpoint must be redis://host:port without credentials");
        }
        return InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort() < 0 ? DEFAULT_PORT : uri.getPort());
    }

    static RedisConnection open(InetSocketAddress address, Instant deadline, Clock clock, long maxResponseBytes) {
        Socket socket = new Socket();
        try {
            socket.connect(
                    new InetSocketAddress(address.getHostString(), address.getPort()),
                    remainingMillis(clock, deadline));
            socket.setTcpNoDelay(true);
            return new RedisConnection(socket, clock, maxResponseBytes);
        } catch (SocketTimeoutException ex) {
            close(socket);
            throw new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
        } catch (ConnectException | UnknownHostException ex) {
            close(socket);
            throw new ProviderCallException(ErrorCode.CONNECTION_FAILED, "Provider could not be reached");
        } catch (IOException ex) {
            close(socket);
            throw new ProviderCallException(ErrorCode.CONNECTION_FAILED, "Provider could not be reached");
        }
    }

    /**
     * 发送一条白名单命令并读取其应答。
     *
     * @return 简单字符串或批量字符串为 String（空批量为 null），整数为 Long，数组为 List（空数组为 null）
     * @throws RedisErrorReply Redis 返回错误应答
     * @throws ProviderCallException 超时、连接中断、应答过大或不合法
     */
    Object call(RedisCommand command, Instant deadline, String... arguments) {
        List<String> words = new ArrayList<>(command.words());
        words.addAll(List.of(arguments));
        try {
            socket.setSoTimeout(remainingMillis(clock, deadline));
            out.write(encode(words));
            out.flush();
            return read(0);
        } catch (SocketTimeoutException ex) {
            throw new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
        } catch (IOException ex) {
            throw new ProviderCallException(ErrorCode.CONNECTION_FAILED, "Provider connection was interrupted");
        }
    }

    static byte[] encode(List<String> words) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.writeBytes(("*" + words.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String word : words) {
            byte[] bytes = word.getBytes(StandardCharsets.UTF_8);
            buffer.writeBytes(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            buffer.writeBytes(bytes);
            buffer.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return buffer.toByteArray();
    }

    private Object read(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw invalid();
        }
        int type = next();
        String line = readLine();
        return switch (type) {
            case '+' -> line;
            case '-' -> throw new RedisErrorReply(line);
            case ':' -> parseLong(line);
            case '$' -> {
                long length = parseLong(line);
                if (length < 0) {
                    yield null;
                }
                charge(length + 2);
                byte[] bytes = in.readNBytes((int) length);
                if (bytes.length != length || in.read() != '\r' || in.read() != '\n') {
                    throw invalid();
                }
                yield new String(bytes, StandardCharsets.UTF_8);
            }
            case '*' -> {
                long count = parseLong(line);
                if (count < 0) {
                    yield null;
                }
                charge(count);
                List<Object> items = new ArrayList<>((int) Math.min(count, 1024));
                for (long i = 0; i < count; i++) {
                    items.add(read(depth + 1));
                }
                yield items;
            }
            default -> throw invalid();
        };
    }

    private int next() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("connection closed");
        }
        charge(1);
        return b;
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = next();
            if (b == '\r') {
                if (next() != '\n') {
                    throw invalid();
                }
                return line.toString();
            }
            if (line.length() >= MAX_LINE) {
                throw invalid();
            }
            line.append((char) b);
        }
    }

    private void charge(long bytes) {
        bytesRead += bytes;
        if (bytesRead > maxResponseBytes) {
            throw new ProviderCallException(ErrorCode.RESULT_TOO_LARGE, "Provider response exceeded the size limit");
        }
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    private static ProviderCallException invalid() {
        return new ProviderCallException(ErrorCode.PROVIDER_RESPONSE_INVALID, "Redis reply is not valid RESP");
    }

    private static int remainingMillis(Clock clock, Instant deadline) {
        long millis = Duration.between(clock.instant(), deadline).toMillis();
        if (millis <= 0) {
            throw new ProviderCallException(ErrorCode.TIMEOUT, "Provider call exceeded the capability timeout");
        }
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    @Override
    public void close() {
        close(socket);
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 只读连接，关闭失败不影响结果
        }
    }
}
