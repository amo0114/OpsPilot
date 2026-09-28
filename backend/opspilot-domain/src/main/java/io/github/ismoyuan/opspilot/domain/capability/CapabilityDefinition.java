package io.github.ismoyuan.opspilot.domain.capability;

import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 一个 Capability 在 Java Registry 中的完整定义（06 §14）：mode 随键冻结，适用的资源类型、请求/结果 Schema、可执行它的 Provider
 * 类型、超时、是否需要人工审批与默认风险。CHANGE 能力必须人工审批（06 §12、CAP-INV-014）。
 */
public record CapabilityDefinition(
        CapabilityKey key,
        Set<ResourceType> supportedResourceTypes,
        CapabilitySchema requestSchema,
        CapabilitySchema resultSchema,
        Set<ProviderType> supportedProviderTypes,
        Duration timeout,
        boolean requiresApproval,
        RiskLevel defaultRiskLevel) {

    public CapabilityDefinition {
        Objects.requireNonNull(key, "key");
        supportedResourceTypes = Set.copyOf(Objects.requireNonNull(supportedResourceTypes, "supportedResourceTypes"));
        Objects.requireNonNull(requestSchema, "requestSchema");
        Objects.requireNonNull(resultSchema, "resultSchema");
        supportedProviderTypes = Set.copyOf(Objects.requireNonNull(supportedProviderTypes, "supportedProviderTypes"));
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(defaultRiskLevel, "defaultRiskLevel");
        if (supportedResourceTypes.isEmpty() || supportedProviderTypes.isEmpty()) {
            throw new IllegalArgumentException(key.key() + " must support at least one resource and provider type");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(key.key() + " timeout must be positive");
        }
        if (key.mode() == CapabilityMode.CHANGE && !requiresApproval) {
            throw new IllegalArgumentException(key.key() + " is a CHANGE capability and must require approval");
        }
    }

    public CapabilityMode mode() {
        return key.mode();
    }

    public boolean supports(ResourceType type) {
        return supportedResourceTypes.contains(type);
    }

    public boolean supports(ProviderType type) {
        return supportedProviderTypes.contains(type);
    }
}
