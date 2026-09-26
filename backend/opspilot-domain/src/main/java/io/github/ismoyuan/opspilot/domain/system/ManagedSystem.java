package io.github.ismoyuan.opspilot.domain.system;

import java.util.Objects;

/**
 * OpsPilot 管理和调查的业务系统（03 §6）。systemKey 是稳定机器标识，name 仅用于展示。
 *
 * @param description 可为空
 * @param version 对应 lock_version
 */
public record ManagedSystem(
        long id,
        String systemKey,
        String name,
        String description,
        String environment,
        SystemStatus status,
        long version) {

    public ManagedSystem {
        Objects.requireNonNull(systemKey, "systemKey");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(status, "status");
    }

    /** 只有 ACTIVE 系统可以创建 Incident（05 §21）。 */
    public boolean isActive() {
        return status == SystemStatus.ACTIVE;
    }
}
