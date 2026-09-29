package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.infrastructure.provider.RedisConnection.RedisErrorReply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 最小 RESP2 客户端：命令白名单、编码、各类应答解析、错误应答、大小与深度上限、超时与连接失败、端点校验。 */
class RedisConnectionTest {

    private ServerSocket server;
    private final AtomicReference<byte[]> reply = new AtomicReference<>();
    private final AtomicReference<byte[]> received = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread.ofVirtual().start(() -> serve(socket));
                } catch (IOException ex) {
                    return; // 服务关闭
                }
            }
        });
    }

    private void serve(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n = in.read(buffer);
            request.write(buffer, 0, Math.max(n, 0));
            received.set(request.toByteArray());
            byte[] answer = reply.get();
            if (answer != null) {
                socket.getOutputStream().write(answer);
                socket.getOutputStream().flush();
            }
            Thread.sleep(2_000); // 不回答时让客户端超时
        } catch (Exception ex) {
            // 客户端已关闭
        }
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    /** 客户端能发送的命令恰好是白名单：没有 GET、KEYS、SCAN、SET、DEL、CONFIG、XRANGE 等。 */
    @Test
    void onlyWhitelistedCommandsExist() {
        Set<String> commands = Arrays.stream(RedisCommand.values())
                .map(command -> String.join(" ", command.words()))
                .collect(Collectors.toSet());
        assertThat(commands)
                .containsExactlyInAnyOrder("AUTH", "PING", "INFO", "XINFO STREAM", "XINFO GROUPS", "XINFO CONSUMERS");
    }

    @Test
    void commandsAreEncodedAsRespArraysOfBulkStrings() {
        reply.set("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
        try (RedisConnection redis = open()) {
            assertThat(redis.call(RedisCommand.XINFO_GROUPS, deadline(), "shortlink:统计"))
                    .isEqualTo("PONG");
        }
        assertThat(new String(received.get(), StandardCharsets.UTF_8))
                .isEqualTo("*3\r\n$5\r\nXINFO\r\n$6\r\nGROUPS\r\n$16\r\nshortlink:统计\r\n");
    }

    @Test
    void repliesAreDecodedByType() {
        assertThat(answer(":42\r\n")).isEqualTo(42L);
        assertThat(answer("$5\r\nhello\r\n")).isEqualTo("hello");
        assertThat(answer("$-1\r\n")).isNull();
        assertThat(answer("*-1\r\n")).isNull();
        assertThat(answer("*3\r\n$4\r\nname\r\n:7\r\n*2\r\n+a\r\n$-1\r\n"))
                .isEqualTo(List.of("name", 7L, Arrays.asList("a", null)));
        assertThatThrownBy(() -> answer("-NOPERM this user has no permissions to run the 'info' command\r\n"))
                .isInstanceOfSatisfying(
                        RedisErrorReply.class, ex -> assertThat(ex.code()).isEqualTo("NOPERM"));
    }

    @Test
    void malformedOversizedOrTooDeepRepliesAreRejected() {
        assertFailure("?what\r\n", ErrorCode.PROVIDER_RESPONSE_INVALID, 1024);
        assertFailure(":abc\r\n", ErrorCode.PROVIDER_RESPONSE_INVALID, 1024);
        assertFailure("$3\r\nabcdef\r\n", ErrorCode.PROVIDER_RESPONSE_INVALID, 1024);
        assertFailure("$100\r\n" + "x".repeat(100) + "\r\n", ErrorCode.RESULT_TOO_LARGE, 64);
        assertFailure("*1\r\n".repeat(10) + ":1\r\n", ErrorCode.PROVIDER_RESPONSE_INVALID, 1024);
    }

    @Test
    void aSilentServerTimesOutAndAClosedPortIsAConnectionFailure() throws IOException {
        reply.set(null);
        long started = System.nanoTime();
        try (RedisConnection redis = open()) {
            assertThatThrownBy(() -> redis.call(RedisCommand.PING, Instant.now().plusMillis(300)))
                    .isInstanceOfSatisfying(
                            ProviderCallException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TIMEOUT));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));

        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        assertThatThrownBy(() -> RedisConnection.open(
                        InetSocketAddress.createUnresolved("127.0.0.1", closed), deadline(), Clock.systemUTC(), 1024))
                .isInstanceOfSatisfying(
                        ProviderCallException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONNECTION_FAILED));
    }

    @Test
    void endpointsMustBePlainRedisUrls() {
        assertThat(RedisConnection.address("redis://redis-proxy:6379")).hasToString("redis-proxy/<unresolved>:6379");
        assertThat(RedisConnection.address("redis://redis").getPort()).isEqualTo(6379);
        for (String endpoint : new String[] {
            "redis://:pw@redis:6379",
            "redis://redis:6379/1",
            "rediss://redis:6379",
            "http://redis:6379",
            "redis://redis?x=1",
            "redis://redis:65536",
            "redis://redis:0"
        }) {
            assertThatThrownBy(() -> RedisConnection.address(endpoint))
                    .as(endpoint)
                    .isInstanceOfSatisfying(
                            ProviderCallException.class,
                            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_BINDING));
        }
    }

    private Object answer(String resp) {
        reply.set(resp.getBytes(StandardCharsets.UTF_8));
        try (RedisConnection redis = open()) {
            return redis.call(RedisCommand.PING, deadline());
        }
    }

    private void assertFailure(String resp, ErrorCode code, long limit) {
        reply.set(resp.getBytes(StandardCharsets.UTF_8));
        try (RedisConnection redis = RedisConnection.open(
                InetSocketAddress.createUnresolved("127.0.0.1", server.getLocalPort()),
                deadline(),
                Clock.systemUTC(),
                limit)) {
            assertThatThrownBy(() -> redis.call(RedisCommand.PING, deadline()))
                    .as(resp)
                    .isInstanceOfSatisfying(
                            ProviderCallException.class,
                            ex -> assertThat(ex.code()).isEqualTo(code));
        }
    }

    private RedisConnection open() {
        return RedisConnection.open(
                InetSocketAddress.createUnresolved("127.0.0.1", server.getLocalPort()),
                deadline(),
                Clock.systemUTC(),
                1024 * 1024);
    }

    private static Instant deadline() {
        return Instant.now().plusSeconds(5);
    }
}
