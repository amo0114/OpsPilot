package io.github.ismoyuan.opspilot.application.system.query;

import java.util.List;

/** 组件及其已启用能力绑定的原始键；能力是否真实存在由查询服务按 CapabilityKey 判定。 */
public record ResourceCapabilityProjection(ResourceSummaryView resource, List<String> enabledCapabilityKeys) {

    public ResourceCapabilityProjection {
        enabledCapabilityKeys = List.copyOf(enabledCapabilityKeys);
    }
}
