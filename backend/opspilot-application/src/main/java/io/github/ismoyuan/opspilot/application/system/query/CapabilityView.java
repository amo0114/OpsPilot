package io.github.ismoyuan.opspilot.application.system.query;

import io.github.ismoyuan.opspilot.domain.capability.CapabilityMode;

/** 组件可用能力（05 §17）。 */
public record CapabilityView(String key, CapabilityMode mode) {}
