package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.system.CapabilityBindingRepository;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityDefinition;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityMode;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityRegistry;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.CapabilityBinding;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 调查中某资源能否使用某 Capability 的统一判定（06 §16 ①～⑥、CAP-INV-005/016）：AI 可见的 Descriptor（TASK-045）与调用准入
 * （TASK-048）用同一套规则，避免两处漂移。依次为：Registry 已注册 → mode=OBSERVE → 资源属于本系统 → ACTIVE → 类型受支持 →
 * CapabilityBinding 已启用 → 唯一 Provider。只读、无事务要求；准入时由调用方在其事务内调用。
 */
@Service
public class CapabilityAccess {

    private final CapabilityRegistry registry;
    private final CapabilityBindingRepository bindings;
    private final CapabilityProviderResolver providers;

    public CapabilityAccess(
            CapabilityRegistry registry, CapabilityBindingRepository bindings, CapabilityProviderResolver providers) {
        this.registry = registry;
        this.bindings = bindings;
        this.providers = providers;
    }

    /** 判定结果：允许时带出定义与唯一 Provider，拒绝时带出错误码与原因（不含配置内容）。 */
    public sealed interface Decision permits Allowed, Denied {}

    public record Allowed(ManagedResource resource, CapabilityDefinition definition, ProviderBinding provider)
            implements Decision {}

    public record Denied(ErrorCode code, String reason) implements Decision {}

    /**
     * @param managedSystemId Incident 所属系统
     * @param resource 目标资源；为空表示按 id 未找到
     */
    public Decision evaluate(long managedSystemId, Optional<ManagedResource> resource, String capabilityKey) {
        Optional<CapabilityDefinition> found = registry.find(capabilityKey);
        if (found.isEmpty()) {
            return new Denied(ErrorCode.CAPABILITY_NOT_FOUND, "NOT_REGISTERED");
        }
        CapabilityDefinition definition = found.get();
        if (definition.mode() != CapabilityMode.OBSERVE) {
            return new Denied(ErrorCode.AI_INTENT_NOT_ALLOWED, "CHANGE_NOT_ALLOWED_IN_INVESTIGATION");
        }
        if (resource.isEmpty() || resource.get().managedSystemId() != managedSystemId) {
            return new Denied(ErrorCode.CAPABILITY_NOT_ALLOWED, "RESOURCE_NOT_IN_SYSTEM");
        }
        ManagedResource target = resource.get();
        if (!target.isActive()) {
            return new Denied(ErrorCode.CAPABILITY_NOT_ALLOWED, "RESOURCE_NOT_ACTIVE");
        }
        if (!definition.supports(target.resourceType())) {
            return new Denied(ErrorCode.CAPABILITY_NOT_ALLOWED, "RESOURCE_TYPE_NOT_SUPPORTED");
        }
        boolean enabled = bindings.findByResourceIdAndCapabilityKey(
                        target.id(), definition.key().key())
                .map(CapabilityBinding::enabled)
                .orElse(false);
        if (!enabled) {
            return new Denied(ErrorCode.CAPABILITY_NOT_BOUND, "BINDING_MISSING_OR_DISABLED");
        }
        try {
            return new Allowed(target, definition, providers.resolve(target, definition));
        } catch (ApplicationException ex) {
            Object reason = ex.details().get("reason");
            return new Denied(ex.errorCode(), reason == null ? null : reason.toString());
        }
    }
}
