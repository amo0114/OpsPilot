package io.github.ismoyuan.opspilot.domain.system.binding;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 服务资源对应的 Docker 容器（06 §94）；service.inspect/service.restart 的请求中不存在容器名，只由此解析。
 *
 * @param containerName Docker 容器名
 */
public record DockerResourceBindingV1(String containerName) implements ResourceSelector {

    public static final String SCHEMA_NAME = "docker.resource.binding";
    public static final int SCHEMA_VERSION = 1;

    /** Docker 容器名规则 [a-zA-Z0-9][a-zA-Z0-9_.-]+，另限长度。 */
    private static final Pattern CONTAINER_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{1,127}");

    public DockerResourceBindingV1 {
        Objects.requireNonNull(containerName, "containerName");
        if (!CONTAINER_NAME.matcher(containerName).matches()) {
            throw new IllegalArgumentException("containerName is invalid");
        }
    }
}
