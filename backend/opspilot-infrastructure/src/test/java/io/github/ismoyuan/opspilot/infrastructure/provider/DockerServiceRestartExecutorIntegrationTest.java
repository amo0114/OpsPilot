package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor.Failed;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor.Resolved;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor.RestartOutcome;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor.Succeeded;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor.Uncertain;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor.Unresolved;
import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector.Inspected;
import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector.NotInspected;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ConfigSchema;
import io.github.ismoyuan.opspilot.domain.system.ConnectionStatus;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
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
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 08 TASK-070、TASK-072：service.restart 与执行结果核对的只读检查经 Docker Engine API 发出（本机 unix socket，与 TASK-057 相同的 Docker 依赖）。真实容器验证目标解析与
 * 实际重启；本地 unix socket 替身验证请求形态与结果分类——请求开始写出之前的失败与 Docker 的明确拒绝为确定失败，写出之后的超时或
 * 断连为结果未知。
 */
class DockerServiceRestartExecutorIntegrationTest {

    static final String SOCKET = "unix:///var/run/docker.sock";
    static final String CONTAINER = "opspilot-t070-" + ProcessHandle.current().pid();
    static final String ANY_ID = "a".repeat(64);

    private final DockerServiceRestartExecutor executor = new DockerServiceRestartExecutor(
            new DockerEngineClient(Clock.systemUTC(), 8L * 1024 * 1024),
            ProviderInvocations.noCredentials(),
            Clock.systemUTC());

    private final DockerServiceRuntimeInspector inspector = new DockerServiceRuntimeInspector(
            new DockerEngineClient(Clock.systemUTC(), 8L * 1024 * 1024), ProviderInvocations.noCredentials());

    @BeforeAll
    static void startContainer() throws Exception {
        docker("run", "-d", "--name", CONTAINER, "redis:7.4.5");
    }

    @AfterAll
    static void removeContainer() throws Exception {
        docker("rm", "-f", CONTAINER);
    }

    @Test
    void theConfiguredContainerIsResolvedToItsIdAndRestartedByThatId() throws Exception {
        String id = docker("inspect", "-f", "{{.Id}}", CONTAINER);
        String startedBefore = docker("inspect", "-f", "{{.State.StartedAt}}", CONTAINER);

        assertThat(executor.resolveTarget(connection(SOCKET), CONTAINER, soon()))
                .isEqualTo(new Resolved(id));
        RestartOutcome outcome =
                executor.restart(connection(SOCKET), id, Instant.now().plusSeconds(30));

        assertThat(outcome).isInstanceOfSatisfying(Succeeded.class, succeeded -> {
            assertThat(succeeded.result().provider()).isEqualTo("DOCKER");
            assertThat(succeeded.result().containerId()).isEqualTo(id);
            assertThat(succeeded.result().completedAt())
                    .isAfterOrEqualTo(succeeded.result().restartRequestedAt());
        });
        assertThat(docker("inspect", "-f", "{{.State.Status}}", CONTAINER)).isEqualTo("running");
        assertThat(docker("inspect", "-f", "{{.State.StartedAt}}", CONTAINER)).isNotEqualTo(startedBefore);
    }

    @Test
    void missingContainersAreDefiniteFailures() {
        assertThat(executor.resolveTarget(connection(SOCKET), CONTAINER + "-missing", soon()))
                .isEqualTo(new Unresolved(ErrorCode.RESOURCE_NOT_FOUND, "Container does not exist"));
        assertThat(executor.restart(connection(SOCKET), ANY_ID, soon()))
                .isEqualTo(new Failed(ErrorCode.RESOURCE_NOT_FOUND, "Container does not exist"));
    }

    /** 只以 id 发出唯一的 POST restart 请求，停止宽限 10 秒；不接受容器名，也不接受非法端点或带凭据的连接。 */
    @Test
    void onlyTheRestartOfAResolvedIdIsSent(@TempDir Path temp) throws Exception {
        Path socket = temp.resolve("docker.sock");
        List<String> requests = new CopyOnWriteArrayList<>();
        try (ServerSocketChannel ignored = serve(socket, http("204 No Content", ""), requests)) {
            assertThat(executor.restart(connection("unix://" + socket), ANY_ID, soon()))
                    .isInstanceOf(Succeeded.class);
        }
        assertThat(requests)
                .singleElement()
                .satisfies(request -> assertThat(request)
                        .startsWith("POST /containers/" + ANY_ID + "/restart?t=10 HTTP/1.1\r\n")
                        .contains("Content-Length: 0\r\n"));

        assertThat(executor.restart(connection("unix://" + socket), CONTAINER, soon()))
                .isEqualTo(new Failed(ErrorCode.INVALID_BINDING, "Container id is invalid"));
        assertThat(executor.restart(connection("tcp://docker:2375"), ANY_ID, soon()))
                .isInstanceOfSatisfying(
                        Failed.class, failed -> assertThat(failed.code()).isEqualTo(ErrorCode.INVALID_BINDING));
    }

    @Test
    void refusalsFromDockerAreDefiniteFailures(@TempDir Path temp) throws Exception {
        for (Object[] answer : List.of(
                new Object[] {"500 Internal Server Error", ErrorCode.PROVIDER_UNAVAILABLE},
                new Object[] {"409 Conflict", ErrorCode.QUERY_REJECTED})) {
            Path socket = temp.resolve(answer[1] + ".sock");
            try (ServerSocketChannel ignored = serve(socket, http((String) answer[0], "{}"), new ArrayList<>())) {
                assertThat(executor.restart(connection("unix://" + socket), ANY_ID, soon()))
                        .isInstanceOfSatisfying(
                                Failed.class,
                                failed -> assertThat(failed.code()).isEqualTo(answer[1]));
            }
        }
    }

    /** 04 §82：核对以准入时解析的容器 id 只读取身份、运行状态与启动时间；不接受名称，容器不存在不是数据。 */
    @Test
    void theRuntimeInspectorReadsTheAdmittedContainerById() throws Exception {
        String id = docker("inspect", "-f", "{{.Id}}", CONTAINER);
        Instant startedAt = Instant.parse(docker("inspect", "-f", "{{.State.StartedAt}}", CONTAINER));

        assertThat(inspector.inspect(connection(SOCKET), id, soon()))
                .isEqualTo(new Inspected(id, RuntimeState.RUNNING, startedAt));
        assertThat(inspector.inspect(connection(SOCKET), ANY_ID, soon()))
                .isEqualTo(new NotInspected(ErrorCode.RESOURCE_NOT_FOUND, "Container does not exist"));
        assertThat(inspector.inspect(connection(SOCKET), CONTAINER, soon()))
                .isEqualTo(new NotInspected(ErrorCode.INVALID_BINDING, "Container id is invalid"));
        assertThat(inspector.inspect(connection("tcp://docker:2375"), id, soon()))
                .isInstanceOfSatisfying(
                        NotInspected.class, failed -> assertThat(failed.code()).isEqualTo(ErrorCode.INVALID_BINDING));
    }

    /** 只发出 GET inspect；非法、缺字段或出错的应答与超时都只是“没有得到数据”，不会被当作事实。 */
    @Test
    void runtimeInspectionsOnlyReadAndNeverGuess(@TempDir Path temp) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        String valid =
                "{\"Id\":\"" + ANY_ID + "\",\"State\":{\"Status\":\"exited\",\"StartedAt\":\"0001-01-01T00:00:00Z\"}}";
        Object[][] answers = {
            {http("200 OK", valid), new Inspected(ANY_ID, RuntimeState.STOPPED, null)},
            {http("200 OK", "not json"), ErrorCode.PROVIDER_RESPONSE_INVALID},
            {http("200 OK", "{\"Id\":\"" + ANY_ID + "\"}"), ErrorCode.PROVIDER_RESPONSE_INVALID},
            {http("200 OK", "{\"Id\":\"x\",\"State\":{\"Status\":\"running\"}}"), ErrorCode.PROVIDER_RESPONSE_INVALID},
            {
                http("200 OK", "{\"Id\":\"" + ANY_ID + "\",\"State\":{\"Status\":\"running\",\"StartedAt\":7}}"),
                ErrorCode.PROVIDER_RESPONSE_INVALID
            },
            {http("500 Internal Server Error", "{}"), ErrorCode.PROVIDER_UNAVAILABLE},
            {null, ErrorCode.TIMEOUT}
        };
        for (int i = 0; i < answers.length; i++) {
            Path socket = temp.resolve(i + ".sock");
            try (ServerSocketChannel ignored = serve(socket, (byte[]) answers[i][0], requests)) {
                var inspection = inspector.inspect(
                        connection("unix://" + socket), ANY_ID, Instant.now().plusMillis(500));
                if (answers[i][1] instanceof ErrorCode code) {
                    assertThat(inspection)
                            .as("answer %d", i)
                            .isInstanceOfSatisfying(
                                    NotInspected.class,
                                    failed -> assertThat(failed.code()).isEqualTo(code));
                } else {
                    assertThat(inspection).as("answer %d", i).isEqualTo(answers[i][1]);
                }
            }
        }
        assertThat(requests)
                .hasSize(answers.length)
                .allSatisfy(
                        request -> assertThat(request).startsWith("GET /containers/" + ANY_ID + "/json HTTP/1.1\r\n"));
    }

    /** 请求还没有写出：连不上或期限已到，确定没有生效。 */
    @Test
    void failuresBeforeTheRequestIsWrittenAreDefinite(@TempDir Path temp) {
        assertThat(executor.restart(connection("unix://" + temp.resolve("absent.sock")), ANY_ID, soon()))
                .isEqualTo(new Failed(ErrorCode.CONNECTION_FAILED, "Provider could not be reached"));
        assertThat(executor.restart(connection(SOCKET), ANY_ID, Instant.now().minusSeconds(1)))
                .isInstanceOfSatisfying(
                        Failed.class, failed -> assertThat(failed.code()).isEqualTo(ErrorCode.TIMEOUT));
    }

    /** 请求已经写出：不应答直到期限、或读完请求就断开，远端可能已经执行——结果未知，不当作失败。 */
    @Test
    void failuresAfterTheRequestIsWrittenAreUncertain(@TempDir Path temp) throws Exception {
        Path silent = temp.resolve("silent.sock");
        Path hangup = temp.resolve("hangup.sock");
        try (ServerSocketChannel quiet = serve(silent, null, new ArrayList<>());
                ServerSocketChannel closing = serve(hangup, new byte[0], new ArrayList<>())) {
            assertThat(executor.restart(
                            connection("unix://" + silent),
                            ANY_ID,
                            Instant.now().plusMillis(300)))
                    .isInstanceOfSatisfying(
                            Uncertain.class,
                            uncertain -> assertThat(uncertain.code()).isEqualTo(ErrorCode.TIMEOUT));
            assertThat(executor.restart(connection("unix://" + hangup), ANY_ID, soon()))
                    .isInstanceOf(Uncertain.class);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Instant soon() {
        return Instant.now().plus(Duration.ofSeconds(5));
    }

    private static DataSourceConnection connection(String endpoint) {
        return new DataSourceConnection(
                1,
                "docker-local",
                "Docker",
                ProviderType.DOCKER,
                endpoint,
                null,
                new ConfigSchema("docker.connection.config", 1),
                "{}",
                ConnectionStatus.ACTIVE,
                0);
    }

    private static byte[] http(String status, String body) {
        return ("HTTP/1.1 " + status + "\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** @param answer 为空时读完请求后一直不应答；长度为 0 时读完请求就断开 */
    private static ServerSocketChannel serve(Path path, byte[] answer, List<String> requests) throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(path));
        Thread.ofVirtual().start(() -> {
            while (server.isOpen()) {
                try {
                    SocketChannel client = server.accept();
                    Thread.ofVirtual().start(() -> {
                        try (client) {
                            ByteBuffer buffer = ByteBuffer.allocate(4096);
                            client.read(buffer);
                            requests.add(new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII));
                            if (answer == null) {
                                Thread.sleep(3_000);
                            } else if (answer.length > 0) {
                                client.write(ByteBuffer.wrap(answer));
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

    /** 测试夹具用 docker CLI 准备与核对容器；被测代码只经 Engine API。 */
    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exit = process.waitFor();
        if (!arguments[0].equals("rm")) {
            assertThat(exit).as(String.join(" ", command) + ": " + output).isZero();
        }
        return output;
    }
}
