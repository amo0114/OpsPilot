package io.github.ismoyuan.opspilot.application.system.query;

import io.github.ismoyuan.opspilot.domain.system.ResourceStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;

/** 系统详情中的组件项（05 §16）。 */
public record ResourceSummaryView(String resourceKey, String name, ResourceType resourceType, ResourceStatus status) {}
