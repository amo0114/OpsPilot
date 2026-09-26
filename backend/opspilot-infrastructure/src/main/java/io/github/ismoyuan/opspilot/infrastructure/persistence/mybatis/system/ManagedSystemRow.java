package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

/** managed_system 行；状态保留原文，由仓储转换为枚举。 */
record ManagedSystemRow(
        long id,
        String systemKey,
        String name,
        String description,
        String environment,
        String status,
        long lockVersion) {}
