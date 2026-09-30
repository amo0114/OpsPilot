package io.github.ismoyuan.opspilot.application.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * service.restart.result / 1（06 §109、§118）：重启请求已被 Docker Engine 接受并完成。只表示“重启操作执行成功”，不表示业务已恢复——
 * 恢复由之后的 RecoveryVerification 判定（06 §112）。
 *
 * @param containerId 准入时解析并实际重启的容器
 */
public record ServiceRestartResultV1(
        String provider, String containerId, Instant restartRequestedAt, Instant completedAt) {

    public static final String SCHEMA_NAME = "service.restart.result";
    public static final int SCHEMA_VERSION = 1;
    public static final String DOCKER = "DOCKER";

    public ServiceRestartResultV1 {
        if (!DOCKER.equals(provider)) {
            throw new IllegalArgumentException("provider must be DOCKER");
        }
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(restartRequestedAt, "restartRequestedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(restartRequestedAt)) {
            throw new IllegalArgumentException("completedAt must not be before restartRequestedAt");
        }
    }
}
