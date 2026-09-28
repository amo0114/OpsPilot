package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException;
import io.github.ismoyuan.opspilot.application.system.DataSourceConnectionRepository;
import io.github.ismoyuan.opspilot.application.system.ResourceBindingRepository;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityDefinition;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.ResourceBinding;
import io.github.ismoyuan.opspilot.domain.system.binding.DockerResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.LokiResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.MySqlResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.RedisResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.ResourceSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Provider 解析（08 TASK-046、06 §16 ⑥、§17～§18）：在资源的全部 ResourceBinding 中，数据源连接为 ACTIVE 且类型受该 Capability 支持者
 * 即候选。0 个 → CAPABILITY_PROVIDER_NOT_CONFIGURED；1 个 → 使用；多于 1 个 → CAPABILITY_PROVIDER_AMBIGUOUS，绝不随机选择、也不做
 * primary/fallback。唯一候选的选择器须按该 Provider 类型的 Schema 解码成功并满足该能力所需（queue.inspect 需给出 Stream），
 * 否则同样视为未配置。只负责这一层；Registry、资源状态与类型、CapabilityBinding 由调用方各自判定。只读、无事务要求。
 */
@Service
public class CapabilityProviderResolver {

    private final ResourceBindingRepository bindings;
    private final DataSourceConnectionRepository connections;
    private final SchemaCodecRegistry codecs;

    public CapabilityProviderResolver(
            ResourceBindingRepository bindings,
            DataSourceConnectionRepository connections,
            SchemaCodecRegistry codecs) {
        this.bindings = bindings;
        this.connections = connections;
        this.codecs = codecs;
    }

    /**
     * @throws ApplicationException CAPABILITY_PROVIDER_NOT_CONFIGURED 或 CAPABILITY_PROVIDER_AMBIGUOUS；details 只含资源键、
     *     能力键与原因（候选数），不含端点、凭据或选择器内容
     */
    public ProviderBinding resolve(ManagedResource resource, CapabilityDefinition definition) {
        List<ResourceBinding> candidateBindings = new ArrayList<>();
        List<DataSourceConnection> candidateConnections = new ArrayList<>();
        for (ResourceBinding binding : bindings.findAllByResourceId(resource.id())) {
            connections
                    .findById(binding.dataSourceConnectionId())
                    .filter(DataSourceConnection::isActive)
                    .filter(connection -> definition.supports(connection.providerType()))
                    .ifPresent(connection -> {
                        candidateBindings.add(binding);
                        candidateConnections.add(connection);
                    });
        }
        if (candidateBindings.isEmpty()) {
            throw failure(ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED, resource, definition, "NO_ACTIVE_PROVIDER");
        }
        if (candidateBindings.size() > 1) {
            throw failure(ErrorCode.CAPABILITY_PROVIDER_AMBIGUOUS, resource, definition, "MULTIPLE_ACTIVE_PROVIDERS");
        }
        ResourceBinding binding = candidateBindings.getFirst();
        DataSourceConnection connection = candidateConnections.getFirst();
        ResourceSelector selector;
        try {
            selector = codecs.decodeSelector(binding, selectorType(connection.providerType()));
        } catch (SchemaPayloadException ex) {
            throw failure(ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED, resource, definition, "INVALID_SELECTOR");
        }
        if (!sufficient(definition.key(), selector)) {
            throw failure(ErrorCode.CAPABILITY_PROVIDER_NOT_CONFIGURED, resource, definition, "INCOMPLETE_SELECTOR");
        }
        return new ProviderBinding(connection, binding, selector);
    }

    private static Class<? extends ResourceSelector> selectorType(ProviderType type) {
        return switch (type) {
            case PROMETHEUS -> PrometheusResourceBindingV1.class;
            case LOKI -> LokiResourceBindingV1.class;
            case REDIS -> RedisResourceBindingV1.class;
            case MYSQL -> MySqlResourceBindingV1.class;
            case DOCKER -> DockerResourceBindingV1.class;
        };
    }

    /** Stream Key 与消费组由配置提供（06 §84）；缓存资源的 Redis 绑定没有它们，不能用于 queue.inspect。 */
    private static boolean sufficient(CapabilityKey key, ResourceSelector selector) {
        return key != CapabilityKey.QUEUE_INSPECT
                || (selector instanceof RedisResourceBindingV1 redis && redis.hasStream());
    }

    private static ApplicationException failure(
            ErrorCode code, ManagedResource resource, CapabilityDefinition definition, String reason) {
        return new ApplicationException(
                code,
                "Capability provider cannot be resolved: " + reason,
                Map.of(
                        "resourceKey", resource.resourceKey(),
                        "capabilityKey", definition.key().key(),
                        "reason", reason));
    }
}
