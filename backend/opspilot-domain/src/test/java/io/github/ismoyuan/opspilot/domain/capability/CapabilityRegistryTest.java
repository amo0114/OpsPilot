package io.github.ismoyuan.opspilot.domain.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 08 TASK-044、06 §10～§14、§37～§102、§118、§125：冻结的 7 个 Capability 定义与其不变量。 */
class CapabilityRegistryTest {

    private final CapabilityRegistry registry = CapabilityRegistry.v01(CapabilityRegistry.DEFAULT_TIMEOUTS);

    @Test
    void registersExactlyTheSevenFrozenCapabilities() {
        assertThat(registry.all())
                .extracting(definition -> definition.key().key())
                .containsExactly(
                        "metrics.query",
                        "logs.search",
                        "cache.inspect",
                        "database.inspect",
                        "queue.inspect",
                        "service.inspect",
                        "service.restart");
        assertThat(registry.find("service.restart")).isPresent();
        assertThat(registry.find("Service.Restart")).isEmpty();
        assertThat(registry.find("shell.exec")).isEmpty();
    }

    /** 06 §37、§48、§59、§69、§83、§93、§102 的资源类型与 Provider。 */
    @Test
    void resourceAndProviderTypesFollowTheSpecification() {
        assertTypes(
                CapabilityKey.METRICS_QUERY,
                Set.of(
                        ResourceType.SERVICE,
                        ResourceType.CONSUMER,
                        ResourceType.DATABASE,
                        ResourceType.CACHE,
                        ResourceType.MESSAGE_QUEUE,
                        ResourceType.EXTERNAL_DEPENDENCY),
                ProviderType.PROMETHEUS);
        assertTypes(CapabilityKey.LOGS_SEARCH, Set.of(ResourceType.SERVICE, ResourceType.CONSUMER), ProviderType.LOKI);
        assertTypes(CapabilityKey.CACHE_INSPECT, Set.of(ResourceType.CACHE), ProviderType.REDIS);
        assertTypes(CapabilityKey.DATABASE_INSPECT, Set.of(ResourceType.DATABASE), ProviderType.MYSQL);
        assertTypes(CapabilityKey.QUEUE_INSPECT, Set.of(ResourceType.MESSAGE_QUEUE), ProviderType.REDIS);
        assertTypes(
                CapabilityKey.SERVICE_INSPECT,
                Set.of(ResourceType.SERVICE, ResourceType.CONSUMER),
                ProviderType.DOCKER);
        assertTypes(
                CapabilityKey.SERVICE_RESTART,
                Set.of(ResourceType.SERVICE, ResourceType.CONSUMER),
                ProviderType.DOCKER);
    }

    /** 只读能力无需审批、风险 LOW；唯一写能力 service.restart 必须审批、风险 MEDIUM（06 §12～§13、§102）。 */
    @Test
    void approvalAndRiskAreDecidedByJava() {
        for (CapabilityDefinition definition : registry.all()) {
            boolean change = definition.key() == CapabilityKey.SERVICE_RESTART;
            assertThat(definition.mode()).isEqualTo(change ? CapabilityMode.CHANGE : CapabilityMode.OBSERVE);
            assertThat(definition.requiresApproval()).isEqualTo(change);
            assertThat(definition.defaultRiskLevel()).isEqualTo(change ? RiskLevel.MEDIUM : RiskLevel.LOW);
        }
        assertThatThrownBy(() -> new CapabilityDefinition(
                        CapabilityKey.SERVICE_RESTART,
                        Set.of(ResourceType.SERVICE),
                        new CapabilitySchema("service.restart.request", 1),
                        new CapabilitySchema("service.restart.result", 1),
                        Set.of(ProviderType.DOCKER),
                        Duration.ofSeconds(30),
                        false,
                        RiskLevel.MEDIUM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must require approval");
    }

    /** 06 §118 命名与 06 §125 默认超时。 */
    @Test
    void schemasAndDefaultTimeouts() {
        CapabilityDefinition cache = registry.definition(CapabilityKey.CACHE_INSPECT);
        assertThat(cache.requestSchema()).isEqualTo(new CapabilitySchema("cache.inspect.request", 1));
        assertThat(cache.resultSchema()).isEqualTo(new CapabilitySchema("cache.inspect.result", 1));
        assertThat(registry.all())
                .extracting(definition -> definition.timeout().toSeconds())
                .containsExactly(10L, 15L, 5L, 10L, 5L, 5L, 30L);
    }

    /** 超时是配置值：覆盖生效，缺少任一能力或非正值时拒绝建立。 */
    @Test
    void timeoutsComeFromConfigurationAndMustCoverEveryCapability() {
        Map<CapabilityKey, Duration> timeouts = new EnumMap<>(CapabilityRegistry.DEFAULT_TIMEOUTS);
        timeouts.put(CapabilityKey.CACHE_INSPECT, Duration.ofSeconds(7));
        assertThat(CapabilityRegistry.v01(timeouts)
                        .definition(CapabilityKey.CACHE_INSPECT)
                        .timeout())
                .isEqualTo(Duration.ofSeconds(7));

        timeouts.remove(CapabilityKey.LOGS_SEARCH);
        assertThatThrownBy(() -> CapabilityRegistry.v01(timeouts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logs.search");
        timeouts.put(CapabilityKey.LOGS_SEARCH, Duration.ZERO);
        assertThatThrownBy(() -> CapabilityRegistry.v01(timeouts)).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertTypes(CapabilityKey key, Set<ResourceType> resourceTypes, ProviderType provider) {
        CapabilityDefinition definition = registry.definition(key);
        assertThat(definition.supportedResourceTypes()).isEqualTo(resourceTypes);
        assertThat(definition.supportedProviderTypes()).containsExactly(provider);
    }
}
