package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.recovery;

import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyRecord;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Repository;

/** {@link RecoveryPolicyRepository} 的 MyBatis 实现；时间以 UTC 毫秒精度存储（04 §3）。 */
@Repository
class MyBatisRecoveryPolicies implements RecoveryPolicyRepository {

    private final RecoveryPolicyMapper mapper;

    MyBatisRecoveryPolicies(RecoveryPolicyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean lockResource(long managedResourceId) {
        return mapper.lockResource(managedResourceId) != null;
    }

    @Override
    public List<RecoveryPolicyRecord> findActive(long managedResourceId) {
        return mapper.selectActive(managedResourceId).stream()
                .map(row -> new RecoveryPolicyRecord(
                        row.id(),
                        row.managedResourceId(),
                        row.policyKey(),
                        row.name(),
                        row.versionNo(),
                        row.criteriaSchemaName(),
                        row.criteriaSchemaVersion(),
                        row.criteriaPayload(),
                        row.activatedAt().toInstant(ZoneOffset.UTC)))
                .toList();
    }

    @Override
    public int maxVersion(long managedResourceId, String policyKey) {
        return mapper.selectMaxVersion(managedResourceId, policyKey);
    }

    @Override
    public int retireActive(long managedResourceId, Instant retiredAt) {
        return mapper.retireActive(managedResourceId, utc(retiredAt));
    }

    @Override
    public long insertActive(NewRecoveryPolicy policy) {
        RecoveryPolicyMapper.GeneratedKey key = new RecoveryPolicyMapper.GeneratedKey();
        mapper.insertActive(
                key,
                new RecoveryPolicyMapper.PolicyInsert(
                        policy.managedResourceId(),
                        policy.policyKey(),
                        policy.name(),
                        policy.versionNo(),
                        policy.criteriaSchemaName(),
                        policy.criteriaSchemaVersion(),
                        policy.criteriaPayload(),
                        utc(policy.activatedAt())));
        return key.getId();
    }

    private static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
