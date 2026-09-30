package io.github.ismoyuan.opspilot.application.system.query;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.query.PageResult;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryCriterionV1;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryPolicyCriteriaV1;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Systems 读取用例（05 §14～§17）；只读事务让同一请求的多条 SQL 读到一致快照。 */
@Service
public class SystemQueryService {

    private final SystemQueryRepository repository;
    private final SchemaCodecRegistry codecs;

    public SystemQueryService(SystemQueryRepository repository, SchemaCodecRegistry codecs) {
        this.repository = repository;
        this.codecs = codecs;
    }

    /** 分页参数已由 web 边界校验。 */
    @Transactional(readOnly = true)
    public PageResult<SystemSummaryView> listSystems(int page, int size) {
        long total = repository.countSystems();
        List<SystemSummaryView> items = repository.findSystems(Math.multiplyExact(page, size), size);
        return new PageResult<>(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public SystemDetailView getSystem(String systemKey) {
        return repository.findSystemDetail(systemKey).orElseThrow(() -> systemNotFound(systemKey));
    }

    /** Registry 之外或未启用的绑定不作为能力展示（06 §15）。 */
    @Transactional(readOnly = true)
    public ResourceDetailView getResource(String systemKey, String resourceKey) {
        Optional<ResourceCapabilityProjection> found = repository.findResource(systemKey, resourceKey);
        if (found.isEmpty()) {
            if (!repository.systemExists(systemKey)) {
                throw systemNotFound(systemKey);
            }
            throw new ApplicationException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Managed resource not found",
                    Map.of("systemKey", systemKey, "resourceKey", resourceKey));
        }
        ResourceSummaryView resource = found.get().resource();
        List<CapabilityView> capabilities = found.get().enabledCapabilityKeys().stream()
                .flatMap(key -> CapabilityKey.fromKey(key).stream())
                .map(capability -> new CapabilityView(capability.key(), capability.mode()))
                .toList();
        return new ResourceDetailView(
                resource.resourceKey(),
                resource.name(),
                resource.resourceType(),
                resource.status(),
                capabilities,
                recoveryPolicy(found.get().activeRecoveryPolicies()));
    }

    /**
     * 恢复标准摘要（05 §17）：唯一 ACTIVE 策略的名称、版本与按执行顺序排列的各项检查名称；没有或出现多条 ACTIVE 时不展示，
     * 不猜测选择（04 §49）。
     */
    private RecoveryPolicySummaryView recoveryPolicy(List<ActiveRecoveryPolicyProjection> active) {
        if (active.size() != 1) {
            return null;
        }
        ActiveRecoveryPolicyProjection policy = active.getFirst();
        RecoveryPolicyCriteriaV1 criteria = codecs.decode(
                policy.criteriaSchemaName(),
                policy.criteriaSchemaVersion(),
                policy.criteriaPayload(),
                RecoveryPolicyCriteriaV1.class);
        String summary =
                criteria.criteria().stream().map(RecoveryCriterionV1::name).collect(Collectors.joining("；"));
        return new RecoveryPolicySummaryView(policy.name(), policy.versionNo(), summary);
    }

    private static ApplicationException systemNotFound(String systemKey) {
        return new ApplicationException(
                ErrorCode.SYSTEM_NOT_FOUND, "Managed system not found", Map.of("systemKey", systemKey));
    }
}
