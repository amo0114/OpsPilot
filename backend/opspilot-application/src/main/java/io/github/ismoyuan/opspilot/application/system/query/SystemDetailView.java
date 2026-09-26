package io.github.ismoyuan.opspilot.application.system.query;

import io.github.ismoyuan.opspilot.domain.system.SystemStatus;
import java.util.List;

/**
 * 业务系统详情（05 §16）；resources 按 resourceKey 升序。
 *
 * @param description 可为空
 */
public record SystemDetailView(
        String systemKey,
        String name,
        String description,
        String environment,
        SystemStatus status,
        List<ResourceSummaryView> resources) {

    public SystemDetailView {
        resources = List.copyOf(resources);
    }
}
