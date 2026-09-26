package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

/** resource_binding 行。 */
record ResourceBindingRow(
        long id,
        long managedResourceId,
        long dataSourceConnectionId,
        String selectorSchemaName,
        int selectorSchemaVersion,
        String selectorPayload) {}
