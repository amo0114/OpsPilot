package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.HealthStatus;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.infrastructure.provider.RedisConnection.RedisErrorReply;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Demo 靶场控制面的底层访问（08 TASK-093），只供 Fault Lab 注入器使用（结构测试保证）：经 Docker Engine API（unix socket）检查、停止与启动
 * 一个容器，经 Redis 读取一个 Stream 与消费组的统计量。复用调查 Provider 的 Docker/RESP 客户端、命令白名单与响应上限，但端点和凭据由
 * Fault Lab 自己的配置给出，与被调查资源的数据源连接无关；不经 Capability，不产生 Observation。
 *
 * <p>失败抛出 {@link DemoControlException}，说明固定且脱敏（不含端点、凭据或容器 id）。
 */
public final class DemoControlClient {

    /** 容器状态：映射与 service.inspect 相同（{@link DockerServiceInspectProvider}）。 */
    public record ContainerState(
            String containerId,
            RuntimeState runtimeState,
            HealthStatus health,
            Instant startedAt,
            Instant finishedAt) {}

    /**
     * @param lastGeneratedId 最近生成的条目 ID（{@code ms-seq}），从未生成为空
     * @param entriesAdded Stream 累计写入的条目数（XINFO STREAM entries-added，Redis 7.0+）；不提供时为空
     * @param lag 消费组未投递条目数；Redis 不给出时为空
     * @param pending 消费组已投递未确认条目数
     */
    public record StreamStatistics(
            Instant observedAt, String lastGeneratedId, Long entriesAdded, Long lag, long pending) {}

    public static final class DemoControlException extends RuntimeException {

        DemoControlException(String safeMessage) {
            super(safeMessage);
        }
    }

    private final DockerEngineClient docker;
    private final Clock clock;
    private final long maxResponseBytes;
    private final JsonMapper json = JsonMapper.builder().build();

    public DemoControlClient(Clock clock, long maxResponseBytes) {
        this.docker = new DockerEngineClient(clock, maxResponseBytes);
        this.clock = clock;
        this.maxResponseBytes = maxResponseBytes;
    }

    public ContainerState inspectContainer(String dockerEndpoint, String containerName, Instant deadline) {
        DockerEngineClient.Response response = call(
                () -> docker.inspect(DockerEngineClient.socketPath(dockerEndpoint), containerName, deadline),
                "Docker Engine could not be reached");
        if (response.status() == 404) {
            throw new DemoControlException("Container does not exist");
        }
        if (response.status() != 200) {
            throw new DemoControlException("Docker Engine could not inspect the container");
        }
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode id = root.path("Id");
            JsonNode state = root.path("State");
            if (!id.isString()
                    || !DockerEngineClient.CONTAINER_ID.matcher(id.asString()).matches()
                    || !state.isObject()
                    || !state.path("Status").isString()) {
                throw invalidInspect();
            }
            return new ContainerState(
                    id.asString(),
                    DockerServiceInspectProvider.runtimeState(
                            state.path("Status").asString()),
                    DockerServiceInspectProvider.health(state.path("Health")),
                    DockerServiceInspectProvider.time(state.path("StartedAt")),
                    DockerServiceInspectProvider.time(state.path("FinishedAt")));
        } catch (JacksonException | ProviderCallException ex) {
            throw invalidInspect();
        }
    }

    /** 停止容器；已停止（304）也是失败：本次调用没有停止它。 */
    public void stopContainer(String dockerEndpoint, String containerId, int graceSeconds, Instant deadline) {
        DockerEngineClient.Response response = call(
                () -> docker.stop(DockerEngineClient.socketPath(dockerEndpoint), containerId, graceSeconds, deadline),
                "Docker Engine did not confirm the container stop");
        if (response.status() != 204) {
            throw new DemoControlException(
                    response.status() == 304 ? "Container was already stopped" : "Docker Engine rejected the stop");
        }
    }

    /** 启动容器；已在运行（304）视为成功。 */
    public void startContainer(String dockerEndpoint, String containerId, Instant deadline) {
        DockerEngineClient.Response response = call(
                () -> docker.start(DockerEngineClient.socketPath(dockerEndpoint), containerId, deadline),
                "Docker Engine did not confirm the container start");
        if (response.status() != 204 && response.status() != 304) {
            throw new DemoControlException("Docker Engine rejected the start");
        }
    }

    /** XINFO STREAM 与 XINFO GROUPS 的统计字段；消息正文（first-entry/last-entry）在解析后即丢弃。 */
    public StreamStatistics streamStatistics(
            String redisEndpoint,
            String username,
            String password,
            String streamKey,
            String consumerGroup,
            Instant deadline) {
        return call(
                () -> {
                    try (RedisConnection redis = RedisConnection.open(
                            RedisConnection.address(redisEndpoint), deadline, clock, maxResponseBytes)) {
                        if (password != null && !password.isEmpty()) {
                            authenticate(redis, username, password, deadline);
                        }
                        Map<String, Object> stream = pairs(redis.call(RedisCommand.XINFO_STREAM, deadline, streamKey));
                        Object lastGenerated = stream.get("last-generated-id");
                        Object entriesAdded = stream.get("entries-added");
                        if (!(redis.call(RedisCommand.XINFO_GROUPS, deadline, streamKey) instanceof List<?> groups)) {
                            throw new DemoControlException("Redis stream has no consumer groups");
                        }
                        Instant observedAt = clock.instant();
                        for (Object entry : groups) {
                            Map<String, Object> group = pairs(entry);
                            if (consumerGroup.equals(group.get("name"))) {
                                Object lag = group.get("lag");
                                return new StreamStatistics(
                                        observedAt,
                                        entryId(lastGenerated),
                                        entriesAdded == null ? null : number(entriesAdded),
                                        lag == null ? null : number(lag),
                                        number(group.get("pending")));
                            }
                        }
                        throw new DemoControlException("Redis consumer group does not exist");
                    } catch (RedisErrorReply reply) {
                        throw new DemoControlException(
                                reply.mentions("no such key")
                                        ? "Redis stream does not exist"
                                        : "Redis rejected the stream statistics request");
                    }
                },
                "Redis could not be reached");
    }

    private static void authenticate(RedisConnection redis, String username, String password, Instant deadline) {
        try {
            if (username == null || username.isEmpty()) {
                redis.call(RedisCommand.AUTH, deadline, password);
            } else {
                redis.call(RedisCommand.AUTH, deadline, username, password);
            }
        } catch (RedisErrorReply reply) {
            throw new DemoControlException("Redis rejected the credential");
        }
    }

    private interface Call<T> {
        T run();
    }

    /** 底层客户端的失败（连接、超时、协议）统一成固定说明。 */
    private static <T> T call(Call<T> call, String failure) {
        try {
            return call.run();
        } catch (ProviderCallException ex) {
            throw new DemoControlException(failure);
        }
    }

    private static Map<String, Object> pairs(Object reply) {
        if (!(reply instanceof List<?> items) || items.size() % 2 != 0) {
            throw invalidStream();
        }
        Map<String, Object> fields = new HashMap<>();
        for (int i = 0; i < items.size(); i += 2) {
            if (!(items.get(i) instanceof String name)) {
                throw invalidStream();
            }
            fields.put(name, items.get(i + 1));
        }
        return fields;
    }

    private static long number(Object value) {
        if (value instanceof Long number && number >= 0) {
            return number;
        }
        throw invalidStream();
    }

    private static String entryId(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String id) || !id.matches("\\d+-\\d+")) {
            throw invalidStream();
        }
        return id.equals("0-0") ? null : id;
    }

    private static DemoControlException invalidInspect() {
        return new DemoControlException("Docker container inspection is not valid");
    }

    private static DemoControlException invalidStream() {
        return new DemoControlException("Redis stream statistics are not valid");
    }
}
