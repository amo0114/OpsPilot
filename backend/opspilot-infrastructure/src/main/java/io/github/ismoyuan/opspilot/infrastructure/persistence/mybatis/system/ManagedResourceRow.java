package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

/** managed_resource 行；类型与状态保留原文，由仓储转换为枚举。 */
record ManagedResourceRow(
        long id,
        long managedSystemId,
        String resourceKey,
        String name,
        String resourceType,
        String description,
        String status,
        long lockVersion) {}
