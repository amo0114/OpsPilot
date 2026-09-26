package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import io.github.ismoyuan.opspilot.domain.system.SystemStatus;

/** 系统详情投影；id 只用于继续查询该系统的资源，不进入视图。 */
record SystemDetailRow(
        long id, String systemKey, String name, String description, String environment, SystemStatus status) {}
