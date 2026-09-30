package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.execution.ServiceRestartExecutor;
import io.github.ismoyuan.opspilot.application.execution.ServiceRestartResultV1;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.connection.DockerConnectionConfigV1;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * service.restart 的 Docker 执行器（08 TASK-070）：经 Docker Engine API（unix socket，{@link DockerEngineClient}）调用，禁止
 * Runtime.exec、ProcessBuilder、docker CLI 与 shell。目标解析只读取 inspect 的 {@code Id}；重启只对该 id 发出一次
 * {@code POST /containers/{id}/restart?t=10}，不重试。
 *
 * <p>分类：请求开始写出之前的失败（端点非法、连接不上、期限已到）为确定失败；Docker 返回 204 为成功，404 为容器不存在、5xx 为
 * Engine 报错、其他状态为拒绝——均为确定失败；请求开始写出之后超时、断连或响应不可读为结果未知。
 */
final class DockerServiceRestartExecutor implements ServiceRestartExecutor {

    /** Docker 停止宽限（秒）；须小于 service.restart 的调用超时（默认 30 秒，06 §125）。 */
    static final int STOP_TIMEOUT_SECONDS = 10;

    private final DockerEngineClient docker;
    private final ProviderAuthentication authentication;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;

    DockerServiceRestartExecutor(DockerEngineClient docker, ProviderAuthentication authentication, Clock clock) {
        this.docker = docker;
        this.authentication = authentication;
        this.clock = clock;
    }

    @Override
    public TargetResolution resolveTarget(DataSourceConnection connection, String containerName, Instant deadline) {
        try {
            DockerEngineClient.Response response = docker.inspect(socket(connection), containerName, deadline);
            if (response.status() == 404) {
                return new Unresolved(ErrorCode.RESOURCE_NOT_FOUND, "Container does not exist");
            }
            if (response.status() != 200) {
                return new Unresolved(ErrorCode.PROVIDER_UNAVAILABLE, "Docker Engine could not inspect the container");
            }
            JsonNode id;
            try {
                id = json.readTree(response.body()).path("Id");
            } catch (JacksonException ex) {
                return invalidInspect();
            }
            if (!id.isString()
                    || !DockerEngineClient.CONTAINER_ID.matcher(id.asString()).matches()) {
                return invalidInspect();
            }
            return new Resolved(id.asString());
        } catch (ProviderCallException ex) {
            return new Unresolved(ex.code(), ex.getMessage());
        }
    }

    @Override
    public RestartOutcome restart(DataSourceConnection connection, String containerId, Instant deadline) {
        Instant requestedAt = clock.instant();
        DockerEngineClient.Response response;
        try {
            response = docker.restart(socket(connection), containerId, STOP_TIMEOUT_SECONDS, deadline);
        } catch (ProviderCallException ex) {
            return ex.requestSent()
                    ? new Uncertain(ex.code(), ex.getMessage())
                    : new Failed(ex.code(), ex.getMessage());
        }
        return switch (response.status()) {
            case 204 ->
                new Succeeded(new ServiceRestartResultV1(
                        ServiceRestartResultV1.DOCKER, containerId, requestedAt, clock.instant()));
            case 404 -> new Failed(ErrorCode.RESOURCE_NOT_FOUND, "Container does not exist");
            default ->
                response.status() >= 500
                        ? new Failed(ErrorCode.PROVIDER_UNAVAILABLE, "Docker Engine failed to restart the container")
                        : new Failed(ErrorCode.QUERY_REJECTED, "Docker Engine rejected the restart request");
        };
    }

    /** 与 service.inspect 相同的连接约束：Docker 配置合法、不带凭据、端点为 unix socket。 */
    private Path socket(DataSourceConnection connection) {
        authentication.config(connection, DockerConnectionConfigV1.SCHEMA_NAME, DockerConnectionConfigV1.class);
        if (connection.credentialRef() != null) {
            throw new ProviderCallException(
                    ErrorCode.INVALID_BINDING, "Docker socket connections do not use credentials");
        }
        return DockerEngineClient.socketPath(connection.endpoint());
    }

    private static Unresolved invalidInspect() {
        return new Unresolved(ErrorCode.PROVIDER_RESPONSE_INVALID, "Docker inspect did not return a container id");
    }
}
