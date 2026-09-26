package io.github.ismoyuan.opspilot.domain.system;

import java.util.Objects;

/**
 * 某个资源是否允许使用某个 Capability（03 §14、06 §15）。capabilityKey 是否存在于 CapabilityRegistry
 * 由 Registry 判定；未绑定或未启用的能力不得执行（06 §16 ⑤）。
 */
public record CapabilityBinding(long id, long managedResourceId, String capabilityKey, boolean enabled) {

    public CapabilityBinding {
        Objects.requireNonNull(capabilityKey, "capabilityKey");
    }
}
