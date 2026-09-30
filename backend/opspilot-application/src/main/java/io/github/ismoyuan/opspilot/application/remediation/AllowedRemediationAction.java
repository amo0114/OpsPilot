package io.github.ismoyuan.opspilot.application.remediation;

import io.github.ismoyuan.opspilot.domain.capability.CapabilityDefinition;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import java.util.Objects;

/** 一个当前允许 AI 选择的写动作：CHANGE 能力定义与已通过访问判定的目标资源（06 §104）。 */
public record AllowedRemediationAction(CapabilityDefinition definition, ManagedResource resource) {

    public AllowedRemediationAction {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(resource, "resource");
    }

    public boolean matches(String capabilityKey, long resourceId) {
        return definition.key().key().equals(capabilityKey) && resource.id() == resourceId;
    }
}
