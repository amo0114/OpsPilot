package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityArguments;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.ProviderBinding;
import io.github.ismoyuan.opspilot.application.capability.ResolvedWindow;
import io.github.ismoyuan.opspilot.application.capability.provider.ObserveProvider;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityRegistry;
import io.github.ismoyuan.opspilot.domain.system.ConfigSchema;
import io.github.ismoyuan.opspilot.domain.system.ConnectionStatus;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.ResourceBinding;
import io.github.ismoyuan.opspilot.domain.system.ResourceStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import io.github.ismoyuan.opspilot.domain.system.SelectorSchema;
import io.github.ismoyuan.opspilot.domain.system.binding.ResourceSelector;
import io.github.ismoyuan.opspilot.infrastructure.schema.SchemaCodecs;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** 构造 Provider 测试用的已准入调用：受信 Binding、参数、窗口与超时均由测试给定（与准入产出的结构一致）。 */
final class ProviderInvocations {

    private ProviderInvocations() {}

    /** 以调用的能力超时为期限取数（与 ProviderCapabilityInvoker 的期限算法一致）。 */
    static ProviderOutcome fetch(ObserveProvider provider, AdmittedInvocation invocation) {
        return provider.fetch(invocation, Instant.now().plus(invocation.timeout()));
    }

    /** 连接不认证时的认证组件；若被要求解析凭据即测试失败。 */
    static ProviderAuthentication noCredentials() {
        return new ProviderAuthentication(SchemaCodecs.registry(), reference -> {
            throw new AssertionError("no credential expected");
        });
    }

    static AdmittedInvocation admitted(
            CapabilityKey capability,
            ProviderType providerType,
            String endpoint,
            String credentialRef,
            ResourceSelector selector,
            CapabilityArguments arguments,
            ResolvedWindow window,
            Duration timeout) {
        return admitted(capability, providerType, endpoint, credentialRef, "{}", selector, arguments, window, timeout);
    }

    static AdmittedInvocation admitted(
            CapabilityKey capability,
            ProviderType providerType,
            String endpoint,
            String credentialRef,
            String configPayload,
            ResourceSelector selector,
            CapabilityArguments arguments,
            ResolvedWindow window,
            Duration timeout) {
        Map<CapabilityKey, Duration> timeouts = new EnumMap<>(CapabilityRegistry.DEFAULT_TIMEOUTS);
        timeouts.put(capability, timeout);
        DataSourceConnection connection = new DataSourceConnection(
                1,
                "test-" + providerType.name().toLowerCase(),
                "Test",
                providerType,
                endpoint,
                credentialRef,
                new ConfigSchema(providerType.name().toLowerCase() + ".connection.config", 1),
                configPayload,
                ConnectionStatus.ACTIVE,
                0);
        ResourceBinding binding = new ResourceBinding(
                1, 1, 1, new SelectorSchema(providerType.name().toLowerCase() + ".resource.binding", 1), "{}");
        return new AdmittedInvocation(
                81,
                7,
                3,
                1,
                new ManagedResource(
                        1, 1, "redirect-service", "Redirect", ResourceType.SERVICE, null, ResourceStatus.ACTIVE, 0),
                CapabilityRegistry.v01(timeouts).definition(capability),
                new ProviderBinding(connection, binding, selector),
                arguments,
                window,
                Instant.now());
    }
}
