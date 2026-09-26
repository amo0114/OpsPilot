package io.github.ismoyuan.opspilot.domain.system;

import java.util.Objects;

/**
 * 业务系统内可被观察或操作的组件，是所有 Capability 的统一目标（03 §7～§8）。resourceKey 只在所属系统内唯一。
 *
 * @param description 可为空
 * @param version 对应 lock_version
 */
public record ManagedResource(
        long id,
        long managedSystemId,
        String resourceKey,
        String name,
        ResourceType resourceType,
        String description,
        ResourceStatus status,
        long version) {

    public ManagedResource {
        Objects.requireNonNull(resourceKey, "resourceKey");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(status, "status");
    }

    /** Capability 执行前要求目标资源 ACTIVE（06 §16 ③）。 */
    public boolean isActive() {
        return status == ResourceStatus.ACTIVE;
    }

    public boolean belongsTo(ManagedSystem system) {
        return managedSystemId == system.id();
    }
}
