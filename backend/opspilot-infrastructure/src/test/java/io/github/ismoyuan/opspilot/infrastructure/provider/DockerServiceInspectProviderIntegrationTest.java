package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.ServiceInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.HealthStatus;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.binding.DockerResourceBindingV1;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 08 TASK-057：真实 Docker Engine（本机 /var/run/docker.sock）。对真实容器——运行中（带敏感环境变量、挂载与标签）、带健康检查、
 * 以退出码 3 结束、按重启策略重启过、不存在——验证只返回白名单字段与状态映射；另以本地 unix socket 桩验证期限与响应大小上限。
 */
class DockerServiceInspectProviderIntegrationTest {

    static final String SOCKET = "unix:///var/run/docker.sock";
    static final String PREFIX = "opspilot-t057-" + ProcessHandle.current().pid() + "-";
    static final List<String> CREATED = new ArrayList<>();

    @BeforeAll
    static void startContainers() throws Exception {
        docker(
                "run",
                "-d",
                "--name",
                name("running"),
                "-e",
                "APP_SECRET=supersecret-env-value",
                "-v",
                "/tmp:/mnt/opspilot-host-tmp:ro",
                "--label",
                "secret-label=hidden-label-value",
                "redis:7.4.5");
        docker(
                "run",
                "-d",
                "--name",
                name("healthy"),
                "--health-cmd",
                "redis-cli ping",
                "--health-interval",
                "1s",
                "redis:7.4.5");
        docker("run", "--name", name("exited"), "redis:7.4.5", "sh", "-c", "exit 3");
        docker(
                "run",
                "-d",
                "--name",
                name("restarting"),
                "--restart",
                "on-failure:2",
                "redis:7.4.5",
                "sh",
                "-c",
                "exit 4");
        await(() -> docker("inspect", "-f", "{{.State.Health.Status}}", name("healthy"))
                .equals("healthy"));
        await(() -> docker("inspect", "-f", "{{.State.Status}} {{.RestartCount}}", name("restarting"))
                .equals("exited 2"));
    }

    @AfterAll
    static void removeContainers() throws Exception {
        for (String container : CREATED) {
            docker("rm", "-f", container);
        }
    }

    @Test
    void aRunningContainerYieldsOnlyWhitelistedFields() {
        ProviderOutcome.Fetched fetched = fetched("running");
        ServiceInspectResultV1 result = (ServiceInspectResultV1) fetched.result();

        assertThat(result.runtimeState()).isEqualTo(RuntimeState.RUNNING);
        assertThat(result.healthStatus()).isEqualTo(HealthStatus.NOT_CONFIGURED);
        assertThat(result.startedAt())
                .isBefore(Instant.now())
                .isAfter(Instant.now().minusSeconds(600));
        assertThat(result.restartCount()).isZero();
        assertThat(result.image()).isEqualTo("redis:7.4.5");
        assertThat(result.exitCode()).isNull();
        assertThat(result.finishedAt()).isNull();
        // 环境变量、挂载、标签、网络均不出现
        assertThat(result + fetched.rawResult())
                .doesNotContain("supersecret-env-value")
                .doesNotContain("APP_SECRET")
                .doesNotContain("/mnt/opspilot-host-tmp")
                .doesNotContain("hidden-label-value")
                .doesNotContain("IPAddress");
    }

    @Test
    void healthExitCodesAndRestartsAreMapped() {
        ServiceInspectResultV1 healthy =
                (ServiceInspectResultV1) fetched("healthy").result();
        assertThat(healthy.healthStatus()).isEqualTo(HealthStatus.HEALTHY);

        ServiceInspectResultV1 exited =
                (ServiceInspectResultV1) fetched("exited").result();
        assertThat(exited.runtimeState()).isEqualTo(RuntimeState.STOPPED);
        assertThat(exited.exitCode()).isEqualTo(3);
        assertThat(exited.finishedAt()).isAfterOrEqualTo(exited.startedAt());

        ServiceInspectResultV1 restarted =
                (ServiceInspectResultV1) fetched("restarting").result();
        assertThat(restarted.restartCount()).isEqualTo(2);
        assertThat(restarted.exitCode()).isEqualTo(4);
    }

    @Test
    void missingContainersEndpointsAndCredentialsAreMapped() {
        assertThat(fetch(SOCKET, null, PREFIX + "does-not-exist"))
                .isEqualTo(new ProviderOutcome.Failed(ErrorCode.RESOURCE_NOT_FOUND, "Container does not exist"));
        assertThat(fetch("tcp://docker:2375", null, name("running")))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.INVALID_BINDING, "Docker endpoint must be unix:///path/to/docker.sock"));
        assertThat(fetch("unix:///var/run/no-such-docker.sock", null, name("running")))
                .isEqualTo(new ProviderOutcome.Failed(ErrorCode.CONNECTION_FAILED, "Provider could not be reached"));
        assertThat(fetch(SOCKET, "env://DOCKER_TOKEN", name("running")))
                .isEqualTo(new ProviderOutcome.Failed(
                        ErrorCode.INVALID_BINDING, "Docker socket connections do not use credentials"));
    }

    /** 本地 unix socket 桩：接受连接却不应答 → 期限处 TIMEOUT；分块响应超过上限 → RESULT_TOO_LARGE。 */
    @Test
    void theDeadlineAndSizeLimitApplyToTheSocket(@TempDir Path temp) throws Exception {
        Path silent = temp.resolve("silent.sock");
        Path chatty = temp.resolve("chatty.sock");
        try (ServerSocketChannel quiet = serve(silent, null);
                ServerSocketChannel big = serve(chatty, chunkedBody(200_000))) {
            long started = System.nanoTime();
            DockerEngineClient client = new DockerEngineClient(Clock.systemUTC(), 1024 * 1024);
            assertThat(outcome(
                            () -> client.inspect(silent, "redis", Instant.now().plusMillis(300))))
                    .isEqualTo(ErrorCode.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));

            DockerEngineClient small = new DockerEngineClient(Clock.systemUTC(), 1024);
            assertThat(outcome(
                            () -> small.inspect(chatty, "redis", Instant.now().plusSeconds(5))))
                    .isEqualTo(ErrorCode.RESULT_TOO_LARGE);
            DockerEngineClient enough = new DockerEngineClient(Clock.systemUTC(), 1024 * 1024);
            assertThat(enough.inspect(chatty, "redis", Instant.now().plusSeconds(5))
                            .body())
                    .hasSize(200_000);
        }
    }

    /**
     * B18-R1：非法数据不当作事实。RestartCount 缺失、字符串、负数、小数或超出 long 都是 PROVIDER_RESPONSE_INVALID，不报告“重启 0 次”；
     * 可选字段缺失或为 null 是未知，存在但非法同样是 PROVIDER_RESPONSE_INVALID。
     */
    @Test
    void malformedInspectionsAreInvalidAndNeverBecomeFacts(@TempDir Path temp) throws Exception {
        String base = "{\"State\": {\"Status\": \"exited\", \"StartedAt\": \"2026-09-29T10:00:00Z\","
                + " \"FinishedAt\": \"2026-09-29T10:05:00Z\", \"ExitCode\": 3%s}, %s\"Config\": {\"Image\": %s}}";
        ServiceInspectResultV1 valid = (ServiceInspectResultV1)
                ((ProviderOutcome.Fetched) stubbed(temp, String.format(base, "", "\"RestartCount\": 2, ", "\"redis\"")))
                        .result();
        assertThat(valid.restartCount()).isEqualTo(2);
        assertThat(valid.exitCode()).isEqualTo(3);
        assertThat(valid.healthStatus()).isEqualTo(HealthStatus.NOT_CONFIGURED);
        ServiceInspectResultV1 unknowns = (ServiceInspectResultV1) ((ProviderOutcome.Fetched) stubbed(
                        temp,
                        "{\"State\": {\"Status\": \"exited\", \"Health\": null, \"ExitCode\": null,"
                                + " \"StartedAt\": null}, \"RestartCount\": 0, \"Config\": {\"Image\": null}}"))
                .result();
        assertThat(unknowns.exitCode()).isNull();
        assertThat(unknowns.startedAt()).isNull();
        assertThat(unknowns.image()).isNull();

        List<String> malformed = List.of(
                String.format(base, "", "", "\"redis\""), // RestartCount 缺失
                String.format(base, "", "\"RestartCount\": \"2\", ", "\"redis\""),
                String.format(base, "", "\"RestartCount\": -1, ", "\"redis\""),
                String.format(base, "", "\"RestartCount\": 1.5, ", "\"redis\""),
                String.format(base, "", "\"RestartCount\": 99999999999999999999, ", "\"redis\""),
                String.format(base, "", "\"RestartCount\": 2, ", "42"), // Image 不是字符串
                String.format(base, ", \"Health\": {\"Status\": 5}", "\"RestartCount\": 2, ", "\"redis\""),
                String.format(base, ", \"Health\": {}", "\"RestartCount\": 2, ", "\"redis\""),
                String.format(base, ", \"Health\": \"healthy\"", "\"RestartCount\": 2, ", "\"redis\""),
                String.format(base, "", "\"RestartCount\": 2, ", "\"redis\"")
                        .replace("\"ExitCode\": 3", "\"ExitCode\": \"3\""),
                String.format(base, "", "\"RestartCount\": 2, ", "\"redis\"")
                        .replace("\"ExitCode\": 3", "\"ExitCode\": 3000000000"),
                String.format(base, "", "\"RestartCount\": 2, ", "\"redis\"")
                        .replace("\"StartedAt\": \"2026-09-29T10:00:00Z\"", "\"StartedAt\": 123"),
                String.format(base, "", "\"RestartCount\": 2, ", "\"redis\"")
                        .replace("\"FinishedAt\": \"2026-09-29T10:05:00Z\"", "\"FinishedAt\": true"));
        for (String body : malformed) {
            assertThat(stubbed(temp, body))
                    .as(body)
                    .isEqualTo(new ProviderOutcome.Failed(
                            ErrorCode.PROVIDER_RESPONSE_INVALID, "Docker container inspection is not valid"));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 本地 unix socket 桩以 200 返回 body，经 Provider 解析。 */
    private static ProviderOutcome stubbed(Path temp, String body) throws IOException {
        Path socket = temp.resolve("inspect-" + System.nanoTime() + ".sock");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        byte[] response = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + payload.length
                        + "\r\n\r\n" + body)
                .getBytes(StandardCharsets.UTF_8);
        try (ServerSocketChannel ignored = serve(socket, response)) {
            return fetch("unix://" + socket, null, "redis");
        }
    }

    private static ProviderOutcome.Fetched fetched(String suffix) {
        ProviderOutcome outcome = fetch(SOCKET, null, name(suffix));
        assertThat(outcome).as(suffix).isInstanceOf(ProviderOutcome.Fetched.class);
        return (ProviderOutcome.Fetched) outcome;
    }

    private static ProviderOutcome fetch(String endpoint, String credentialRef, String container) {
        AdmittedInvocation invocation = ProviderInvocations.admitted(
                CapabilityKey.SERVICE_INSPECT,
                ProviderType.DOCKER,
                endpoint,
                credentialRef,
                "{}",
                new DockerResourceBindingV1(container),
                new ServiceInspectArgumentsV1(),
                null,
                Duration.ofSeconds(5));
        return ProviderInvocations.fetch(
                new DockerServiceInspectProvider(
                        new DockerEngineClient(Clock.systemUTC(), 8L * 1024 * 1024),
                        ProviderInvocations.noCredentials(),
                        Clock.systemUTC()),
                invocation);
    }

    private static ErrorCode outcome(Runnable call) {
        try {
            call.run();
            return null;
        } catch (ProviderCallException ex) {
            return ex.code();
        }
    }

    private static byte[] chunkedBody(int size) {
        StringBuilder response = new StringBuilder("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");
        int remaining = size;
        while (remaining > 0) {
            int chunk = Math.min(4096, remaining);
            response.append(Integer.toHexString(chunk))
                    .append("\r\n")
                    .append("x".repeat(chunk))
                    .append("\r\n");
            remaining -= chunk;
        }
        response.append("0\r\n\r\n");
        return response.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** answer 为空：接受连接后不应答。 */
    private static ServerSocketChannel serve(Path path, byte[] answer) throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(path));
        Thread.ofVirtual().start(() -> {
            while (server.isOpen()) {
                try {
                    SocketChannel client = server.accept();
                    Thread.ofVirtual().start(() -> {
                        try (client) {
                            client.read(ByteBuffer.allocate(4096));
                            if (answer != null) {
                                client.write(ByteBuffer.wrap(answer));
                            } else {
                                Thread.sleep(3_000);
                            }
                        } catch (Exception ignored) {
                            // 客户端已关闭
                        }
                    });
                } catch (IOException ex) {
                    return;
                }
            }
        });
        return server;
    }

    private static String name(String suffix) {
        return PREFIX + suffix;
    }

    /** 执行 docker CLI；前台 run（容器以非零码退出）与清理用的 rm 不要求退出码为 0。 */
    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        boolean run = arguments[0].equals("run");
        if (run) {
            CREATED.add(arguments[List.of(arguments).indexOf("--name") + 1]);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exit = process.waitFor();
        boolean foreground = run && !List.of(arguments).contains("-d");
        if (!foreground && !arguments[0].equals("rm")) {
            assertThat(exit).as(String.join(" ", command) + ": " + output).isZero();
        }
        return output;
    }

    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        Instant deadline = Instant.now().plusSeconds(60);
        while (!condition.call()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(500);
        }
    }
}
