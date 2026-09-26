package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

/** capability_binding 行。 */
record CapabilityBindingRow(long id, long managedResourceId, String capabilityKey, boolean enabled) {}
