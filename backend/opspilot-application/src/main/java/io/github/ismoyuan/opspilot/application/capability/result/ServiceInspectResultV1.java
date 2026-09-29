package io.github.ismoyuan.opspilot.application.capability.result;

import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import java.time.Instant;
import java.util.Objects;

/**
 * service.inspect.result / 1（06 §96～§97）：容器运行状态的白名单字段；不含 Environment、Mount、Network、Labels（CAP-INV-011）。
 *
 * @param startedAt 未知或从未启动时为空
 * @param exitCode 仍在运行或未知时为空
 * @param finishedAt 未结束或未知时为空
 */
public record ServiceInspectResultV1(
        RuntimeState runtimeState,
        HealthStatus healthStatus,
        Instant startedAt,
        long restartCount,
        String image,
        Integer exitCode,
        Instant finishedAt)
        implements CapabilityResult {

    public static final String SCHEMA_NAME = "service.inspect.result";
    public static final int SCHEMA_VERSION = 1;

    public enum RuntimeState {
        RUNNING,
        STOPPED,
        RESTARTING,
        PAUSED,
        UNKNOWN
    }

    public enum HealthStatus {
        HEALTHY,
        UNHEALTHY,
        STARTING,
        NOT_CONFIGURED,
        UNKNOWN
    }

    public ServiceInspectResultV1 {
        Objects.requireNonNull(runtimeState, "runtimeState");
        Objects.requireNonNull(healthStatus, "healthStatus");
        ResultChecks.nonNegative("restartCount", restartCount);
        ResultChecks.optionalText("image", image);
    }

    @Override
    public CapabilitySchema resultSchema() {
        return new CapabilitySchema(SCHEMA_NAME, SCHEMA_VERSION);
    }
}
