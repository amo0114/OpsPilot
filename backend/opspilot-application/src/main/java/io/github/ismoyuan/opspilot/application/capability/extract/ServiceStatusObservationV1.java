package io.github.ismoyuan.opspilot.application.capability.extract;

import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.HealthStatus;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1.RuntimeState;
import java.time.Instant;
import java.util.Objects;

/** service-status.observation / 1（06 §98、§117）：容器运行状态白名单字段。 */
public record ServiceStatusObservationV1(
        RuntimeState runtimeState,
        HealthStatus healthStatus,
        Instant startedAt,
        long restartCount,
        String image,
        Integer exitCode,
        Instant finishedAt) {

    public static final String SCHEMA_NAME = "service-status.observation";
    public static final int SCHEMA_VERSION = 1;

    public ServiceStatusObservationV1 {
        Objects.requireNonNull(runtimeState, "runtimeState");
        Objects.requireNonNull(healthStatus, "healthStatus");
    }
}
