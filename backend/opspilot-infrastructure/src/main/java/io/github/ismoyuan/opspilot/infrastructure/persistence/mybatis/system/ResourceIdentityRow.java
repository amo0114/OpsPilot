package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import io.github.ismoyuan.opspilot.application.system.query.ResourceSummaryView;
import io.github.ismoyuan.opspilot.domain.system.ResourceStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;

/** 组件详情投影；id 只用于继续查询能力绑定，不进入视图。 */
record ResourceIdentityRow(long id, String resourceKey, String name, ResourceType resourceType, ResourceStatus status) {

    ResourceSummaryView toView() {
        return new ResourceSummaryView(resourceKey, name, resourceType, status);
    }
}
