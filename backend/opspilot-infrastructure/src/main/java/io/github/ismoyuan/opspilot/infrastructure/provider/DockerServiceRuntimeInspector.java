package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.execution.ServiceRuntimeInspector;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import java.time.Instant;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 执行结果核对的 Docker 只读检查（08 TASK-072、04 §82）：以执行准入时解析的容器 id 调用 Engine API inspect（与 service.inspect 同一
 * 客户端、连接约束与状态映射），只读取 {@code Id}、{@code State.Status}、{@code State.StartedAt}。不含写路径、不重试；任何错误都是
 * “没有得到可用数据”，由核对方按未确认处理。
 */
final class DockerServiceRuntimeInspector implements ServiceRuntimeInspector {

    private final DockerEngineClient docker;
    private final ProviderAuthentication authentication;
    private final JsonMapper json = JsonMapper.builder().build();

    DockerServiceRuntimeInspector(DockerEngineClient docker, ProviderAuthentication authentication) {
        this.docker = docker;
        this.authentication = authentication;
    }

    @Override
    public RuntimeInspection inspect(DataSourceConnection connection, String containerId, Instant deadline) {
        if (!DockerEngineClient.CONTAINER_ID.matcher(containerId).matches()) {
            return new NotInspected(ErrorCode.INVALID_BINDING, "Container id is invalid");
        }
        try {
            DockerEngineClient.Response response = docker.inspect(
                    DockerServiceRestartExecutor.socket(authentication, connection), containerId, deadline);
            if (response.status() == 404) {
                return new NotInspected(ErrorCode.RESOURCE_NOT_FOUND, "Container does not exist");
            }
            if (response.status() != 200) {
                return new NotInspected(
                        ErrorCode.PROVIDER_UNAVAILABLE, "Docker Engine could not inspect the container");
            }
            return parse(response.body());
        } catch (ProviderCallException ex) {
            return new NotInspected(ex.code(), ex.getMessage());
        }
    }

    private RuntimeInspection parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException ex) {
            return invalid();
        }
        JsonNode id = root.path("Id");
        JsonNode state = root.path("State");
        if (!id.isString()
                || !DockerEngineClient.CONTAINER_ID.matcher(id.asString()).matches()
                || !state.isObject()
                || !state.path("Status").isString()) {
            return invalid();
        }
        return new Inspected(
                id.asString(),
                DockerServiceInspectProvider.runtimeState(state.path("Status").asString()),
                DockerServiceInspectProvider.time(state.path("StartedAt")));
    }

    private static NotInspected invalid() {
        return new NotInspected(ErrorCode.PROVIDER_RESPONSE_INVALID, "Docker container inspection is not valid");
    }
}
