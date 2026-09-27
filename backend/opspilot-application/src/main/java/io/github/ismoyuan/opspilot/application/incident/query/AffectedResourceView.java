package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.domain.system.ResourceType;

public record AffectedResourceView(String resourceKey, String name, ResourceType resourceType) {}
