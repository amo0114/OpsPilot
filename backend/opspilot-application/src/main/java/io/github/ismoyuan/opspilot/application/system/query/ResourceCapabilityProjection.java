package io.github.ismoyuan.opspilot.application.system.query;

import java.util.List;

/**
 * 组件及其已启用能力绑定的原始键；能力是否真实存在由查询服务按 CapabilityKey 判定。
 *
 * @param activeRecoveryPolicies 该组件 ACTIVE 恢复策略，正常配置下至多一条（04 §49）
 */
public record ResourceCapabilityProjection(
        ResourceSummaryView resource,
        List<String> enabledCapabilityKeys,
        List<ActiveRecoveryPolicyProjection> activeRecoveryPolicies) {

    public ResourceCapabilityProjection {
        enabledCapabilityKeys = List.copyOf(enabledCapabilityKeys);
        activeRecoveryPolicies = List.copyOf(activeRecoveryPolicies);
    }
}
