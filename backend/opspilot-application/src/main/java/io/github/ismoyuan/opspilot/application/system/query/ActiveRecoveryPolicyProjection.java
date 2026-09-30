package io.github.ismoyuan.opspilot.application.system.query;

/** 组件当前 ACTIVE 恢复策略的原始行；Criteria 由查询服务按 Schema 解码后生成摘要（05 §17）。 */
public record ActiveRecoveryPolicyProjection(
        String name, int versionNo, String criteriaSchemaName, int criteriaSchemaVersion, String criteriaPayload) {}
