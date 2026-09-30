package io.github.ismoyuan.opspilot.application.recovery;

import java.time.Instant;
import java.util.Objects;

/**
 * recovery_policy 的一行（04 §48）；Criteria 以原始载荷与伴随 Schema 保存，使用前经 SchemaCodecRegistry 解码为
 * {@link RecoveryPolicyCriteriaV1}，不以 JsonNode 猜字段。
 */
public record RecoveryPolicyRecord(
        long id,
        long managedResourceId,
        String policyKey,
        String name,
        int versionNo,
        String criteriaSchemaName,
        int criteriaSchemaVersion,
        String criteriaPayload,
        Instant activatedAt) {

    public RecoveryPolicyRecord {
        Objects.requireNonNull(policyKey, "policyKey");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(criteriaSchemaName, "criteriaSchemaName");
        Objects.requireNonNull(criteriaPayload, "criteriaPayload");
        Objects.requireNonNull(activatedAt, "activatedAt");
    }
}
