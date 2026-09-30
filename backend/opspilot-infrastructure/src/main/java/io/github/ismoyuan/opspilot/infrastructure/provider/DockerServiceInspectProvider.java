package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.provider.ObserveProvider;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.HealthStatus;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.binding.DockerResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.connection.DockerConnectionConfigV1;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * service.inspect 的 Docker Provider（08 TASK-057、06 §92～§100）。容器名只来自 Binding（06 §94），AI 不提供；只读取 inspect 的白名单
 * 字段——State.Status、State.Health.Status、State.StartedAt/FinishedAt/ExitCode、RestartCount、Config.Image——不读取、不返回
 * Environment、Mounts、NetworkSettings、Labels 等（CAP-INV-011）；原始结果也只含白名单字段。
 *
 * <p>映射：running→RUNNING，exited/dead/created→STOPPED，restarting→RESTARTING，paused→PAUSED，其他→UNKNOWN；无健康检查为
 * NOT_CONFIGURED。只有已停止（exited/dead）时才给出 exitCode 与 finishedAt；从未启动或未结束的时间（Docker 的 0001-01-01）为空。
 * 容器不存在为 RESOURCE_NOT_FOUND。
 *
 * <p>非法数据不当作事实（B18-R1）：RestartCount 必须是非负整数（缺失、字符串、负数、小数都是 PROVIDER_RESPONSE_INVALID，不补 0）；
 * 可选字段缺失或为 null 表示未知，存在但类型或取值不合法（Health 没有字符串 Status、时间不是 ISO 字符串、Image 不是字符串、
 * ExitCode 不是 int 范围内的整数）同样是 PROVIDER_RESPONSE_INVALID。
 */
final class DockerServiceInspectProvider implements ObserveProvider {

    private final DockerEngineClient docker;
    private final ProviderAuthentication authentication;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;

    DockerServiceInspectProvider(DockerEngineClient docker, ProviderAuthentication authentication, Clock clock) {
        this.docker = docker;
        this.authentication = authentication;
        this.clock = clock;
    }

    @Override
    public CapabilityKey capability() {
        return CapabilityKey.SERVICE_INSPECT;
    }

    @Override
    public ProviderOutcome fetch(AdmittedInvocation invocation, Instant deadline) {
        try {
            if (!(invocation.provider().selector() instanceof DockerResourceBindingV1 selector)) {
                throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Resource binding is not a Docker binding");
            }
            DataSourceConnection connection = invocation.provider().connection();
            authentication.config(connection, DockerConnectionConfigV1.SCHEMA_NAME, DockerConnectionConfigV1.class);
            if (connection.credentialRef() != null) {
                throw new ProviderCallException(
                        ErrorCode.INVALID_BINDING, "Docker socket connections do not use credentials");
            }
            Path socket = DockerEngineClient.socketPath(connection.endpoint());
            DockerEngineClient.Response response = docker.inspect(socket, selector.containerName(), deadline);
            Instant observedAt = clock.instant();
            checkStatus(response.status());
            ServiceInspectResultV1 result = parse(response.body());
            return new ProviderOutcome.Fetched(result, rawLines(result), observedAt);
        } catch (ProviderCallException ex) {
            return ex.outcome();
        }
    }

    private ServiceInspectResultV1 parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException ex) {
            throw invalid();
        }
        JsonNode state = root.path("State");
        if (!state.isObject() || !state.path("Status").isString()) {
            throw invalid();
        }
        String status = state.path("Status").asString();
        RuntimeState runtime = runtimeState(status);
        HealthStatus healthStatus = health(state.path("Health"));
        boolean ended = status.equals("exited") || status.equals("dead");
        try {
            return new ServiceInspectResultV1(
                    runtime,
                    healthStatus,
                    time(state.path("StartedAt")),
                    restartCount(root.path("RestartCount")),
                    image(root.path("Config").path("Image")),
                    ended ? exitCode(state.path("ExitCode")) : null,
                    ended ? time(state.path("FinishedAt")) : null);
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    /** Docker State.Status 的映射；执行结果核对（{@link DockerServiceRuntimeInspector}）沿用同一映射。 */
    static RuntimeState runtimeState(String status) {
        return switch (status) {
            case "running" -> RuntimeState.RUNNING;
            case "exited", "dead", "created" -> RuntimeState.STOPPED;
            case "restarting" -> RuntimeState.RESTARTING;
            case "paused" -> RuntimeState.PAUSED;
            default -> RuntimeState.UNKNOWN;
        };
    }

    /** 没有 Health（未配置健康检查）为 NOT_CONFIGURED；有 Health 却没有字符串 Status 是非法数据。 */
    private static HealthStatus health(JsonNode health) {
        if (absent(health)) {
            return HealthStatus.NOT_CONFIGURED;
        }
        JsonNode status = health.path("Status");
        if (!health.isObject() || !status.isString()) {
            throw invalid();
        }
        return switch (status.asString()) {
            case "healthy" -> HealthStatus.HEALTHY;
            case "unhealthy" -> HealthStatus.UNHEALTHY;
            case "starting" -> HealthStatus.STARTING;
            case "none" -> HealthStatus.NOT_CONFIGURED;
            default -> HealthStatus.UNKNOWN;
        };
    }

    /** 必须存在且为非负整数：缺失或非法时不能报告“重启 0 次”。 */
    private static long restartCount(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0) {
            throw invalid();
        }
        return node.asLong();
    }

    private static Integer exitCode(JsonNode node) {
        if (absent(node)) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid();
        }
        return node.asInt();
    }

    private static String image(JsonNode node) {
        if (absent(node)) {
            return null;
        }
        if (!node.isString()) {
            throw invalid();
        }
        return node.asString().isBlank() ? null : node.asString();
    }

    /** 缺失、null、空串或 Docker 的零时间（0001-01-01）为未知；其他非 ISO-8601 字符串或非字符串为非法。 */
    static Instant time(JsonNode node) {
        if (absent(node)) {
            return null;
        }
        if (!node.isString()) {
            throw invalid();
        }
        if (node.asString().isBlank() || node.asString().startsWith("0001-01-01")) {
            return null;
        }
        try {
            return Instant.parse(node.asString());
        } catch (DateTimeParseException ex) {
            throw invalid();
        }
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static void checkStatus(int status) {
        if (status == 200) {
            return;
        }
        if (status == 404) {
            throw new ProviderCallException(ErrorCode.RESOURCE_NOT_FOUND, "Container does not exist");
        }
        if (status == 401 || status == 403) {
            throw new ProviderCallException(ErrorCode.AUTHORIZATION_DENIED, "Docker Engine denied the inspection");
        }
        if (status >= 500) {
            throw new ProviderCallException(ErrorCode.PROVIDER_UNAVAILABLE, "Provider answered HTTP " + status);
        }
        throw new ProviderCallException(ErrorCode.PROVIDER_RESPONSE_INVALID, "Provider answered HTTP " + status);
    }

    private static String rawLines(ServiceInspectResultV1 result) {
        return "runtime_state " + result.runtimeState() + '\n'
                + "health_status " + result.healthStatus() + '\n'
                + "started_at " + result.startedAt() + '\n'
                + "restart_count " + result.restartCount() + '\n'
                + "image " + result.image() + '\n'
                + "exit_code " + result.exitCode() + '\n'
                + "finished_at " + result.finishedAt() + '\n';
    }

    private static ProviderCallException invalid() {
        return new ProviderCallException(
                ErrorCode.PROVIDER_RESPONSE_INVALID, "Docker container inspection is not valid");
    }
}
