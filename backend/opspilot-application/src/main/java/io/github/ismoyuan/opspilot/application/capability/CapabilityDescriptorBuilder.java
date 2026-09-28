package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityDescriptor;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.DatabaseInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InspectionType;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogsSearchArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.incident.IncidentRepository;
import io.github.ismoyuan.opspilot.application.investigation.context.CapabilityDescriptorSource;
import io.github.ismoyuan.opspilot.application.system.CapabilityBindingRepository;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityDefinition;
import io.github.ismoyuan.opspilot.domain.incident.Incident;
import io.github.ismoyuan.opspilot.domain.system.CapabilityBinding;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 可见的 Capability Descriptor（08 TASK-045、06 §16、§22、CAP-INV-005/016）：只从受信配置构造当前实际允许的受控空间。
 * 对 Incident 所属系统中每个 ACTIVE 资源（按 resourceKey），逐个已启用的 CapabilityBinding（按能力键）交 {@link CapabilityAccess}
 * 判定——与调用准入（TASK-048）同一规则：Registry 已注册、mode=OBSERVE（service.restart 永不出现）、资源类型受支持、存在唯一可用
 * Provider（TASK-046）。任一不满足即不进入可执行空间。
 *
 * <p>受控参数域：metricKeys 取资源 Prometheus 绑定中实际声明的指标（按字典序）；windowKeys、severities、inspectionTypes 为冻结枚举全集；
 * 关键字与 limit 边界为协议常量。不包含端点、凭据、查询模板或选择器内容。只读、无事务要求。
 */
@Service
public class CapabilityDescriptorBuilder implements CapabilityDescriptorSource {

    private static final Logger log = LoggerFactory.getLogger(CapabilityDescriptorBuilder.class);

    private final IncidentRepository incidents;
    private final ManagedResourceRepository resources;
    private final CapabilityBindingRepository capabilityBindings;
    private final CapabilityAccess access;

    public CapabilityDescriptorBuilder(
            IncidentRepository incidents,
            ManagedResourceRepository resources,
            CapabilityBindingRepository capabilityBindings,
            CapabilityAccess access) {
        this.incidents = incidents;
        this.resources = resources;
        this.capabilityBindings = capabilityBindings;
        this.access = access;
    }

    @Override
    public List<CapabilityDescriptor> describe(long incidentId) {
        Optional<Incident> incident = incidents.findById(incidentId);
        if (incident.isEmpty()) {
            return List.of();
        }
        long systemId = incident.get().managedSystemId();
        List<CapabilityDescriptor> descriptors = new ArrayList<>();
        for (ManagedResource resource : resources.findAllBySystemId(systemId)) {
            if (!resource.isActive()) {
                continue;
            }
            for (CapabilityBinding binding : capabilityBindings.findAllByResourceId(resource.id())) {
                if (binding.enabled()) {
                    describe(systemId, resource, binding).ifPresent(descriptors::add);
                }
            }
        }
        return List.copyOf(descriptors);
    }

    private Optional<CapabilityDescriptor> describe(
            long systemId, ManagedResource resource, CapabilityBinding binding) {
        CapabilityAccess.Decision decision = access.evaluate(systemId, Optional.of(resource), binding.capabilityKey());
        if (decision instanceof CapabilityAccess.Denied denied) {
            log.debug(
                    "Capability hidden from AI: resourceKey={} capability={} code={} reason={}",
                    resource.resourceKey(),
                    binding.capabilityKey(),
                    denied.code(),
                    denied.reason());
            return Optional.empty();
        }
        CapabilityAccess.Allowed allowed = (CapabilityAccess.Allowed) decision;
        CapabilityDefinition definition = allowed.definition();
        ProviderBinding provider = allowed.provider();
        String key = definition.key().key();
        long id = resource.id();
        String resourceKey = resource.resourceKey();
        return Optional.of(
                switch (definition.key()) {
                    case METRICS_QUERY ->
                        new CapabilityDescriptor.MetricsQuery(
                                key, id, resourceKey, metricKeys(provider), List.of(WindowKey.values()), true);
                    case LOGS_SEARCH ->
                        new CapabilityDescriptor.LogsSearch(
                                key,
                                id,
                                resourceKey,
                                List.of(WindowKey.values()),
                                List.of(LogSeverity.values()),
                                LogsSearchArgumentsV1.MAX_KEYWORDS,
                                LogsSearchArgumentsV1.MAX_KEYWORD_LENGTH);
                    case DATABASE_INSPECT ->
                        new CapabilityDescriptor.DatabaseInspect(
                                key,
                                id,
                                resourceKey,
                                List.of(InspectionType.values()),
                                DatabaseInspectArgumentsV1.LIMIT_MIN,
                                DatabaseInspectArgumentsV1.LIMIT_MAX);
                    case CACHE_INSPECT -> new CapabilityDescriptor.CacheInspect(key, id, resourceKey);
                    case QUEUE_INSPECT -> new CapabilityDescriptor.QueueInspect(key, id, resourceKey);
                    case SERVICE_INSPECT -> new CapabilityDescriptor.ServiceInspect(key, id, resourceKey);
                    case SERVICE_RESTART -> throw new IllegalStateException("CHANGE capability in investigation");
                });
    }

    /** 资源只向 AI 暴露它实际拥有的 MetricKey（06 §39～§40）。 */
    private static List<String> metricKeys(ProviderBinding provider) {
        PrometheusResourceBindingV1 prometheus = (PrometheusResourceBindingV1) provider.selector();
        return prometheus.metrics().keySet().stream().sorted().toList();
    }
}
