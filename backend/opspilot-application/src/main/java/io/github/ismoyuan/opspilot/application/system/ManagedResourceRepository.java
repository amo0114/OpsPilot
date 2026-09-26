package io.github.ismoyuan.opspilot.application.system;

import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import java.util.List;
import java.util.Optional;

/** 系统组件读取端口；resourceKey 只在所属系统内有意义，因此按系统限定查找。 */
public interface ManagedResourceRepository {

    Optional<ManagedResource> findById(long id);

    Optional<ManagedResource> findBySystemIdAndResourceKey(long managedSystemId, String resourceKey);

    /** 按 resourceKey 升序。 */
    List<ManagedResource> findAllBySystemId(long managedSystemId);
}
