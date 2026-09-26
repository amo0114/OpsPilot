package io.github.ismoyuan.opspilot.application.system;

import io.github.ismoyuan.opspilot.domain.system.CapabilityBinding;
import java.util.List;
import java.util.Optional;

/** 资源能力绑定读取端口；返回的绑定可能 enabled=false，是否允许执行由调用方判定。 */
public interface CapabilityBindingRepository {

    Optional<CapabilityBinding> findByResourceIdAndCapabilityKey(long managedResourceId, String capabilityKey);

    /** 按 capabilityKey 升序。 */
    List<CapabilityBinding> findAllByResourceId(long managedResourceId);
}
