package io.github.ismoyuan.opspilot.application.system.query;

import io.github.ismoyuan.opspilot.domain.system.ResourceStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import java.util.List;

/**
 * 系统组件详情（05 §17）。不含任何连接端点或凭据。
 *
 * @param capabilities 已启用且属于 V0.1 能力的绑定，按 key 升序
 * @param recoveryPolicy 可为空：没有唯一 ACTIVE 恢复策略时不展示
 */
public record ResourceDetailView(
        String resourceKey,
        String name,
        ResourceType resourceType,
        ResourceStatus status,
        List<CapabilityView> capabilities,
        RecoveryPolicySummaryView recoveryPolicy) {

    public ResourceDetailView {
        capabilities = List.copyOf(capabilities);
    }
}
