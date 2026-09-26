package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

/** data_source_connection 行；类型与状态保留原文，由仓储转换为枚举。 */
record DataSourceConnectionRow(
        long id,
        String connectionKey,
        String name,
        String providerType,
        String endpoint,
        String credentialRef,
        String configSchemaName,
        int configSchemaVersion,
        String configPayload,
        String status,
        long lockVersion) {}
