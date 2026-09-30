package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 激活一个 RecoveryPolicy 新版本（04 §48～§49、03 §50、08 TASK-076）。一个短事务：锁定稳定存在的资源父行 → 校验
 * （{@link RecoveryPolicyValidator}）→ 把该资源全部 ACTIVE 置为 RETIRED（跨 policy_key）→ 插入同 policy_key 的下一版本为
 * ACTIVE。父行锁让同一资源的激活串行，包括没有旧策略时的并发首次激活，因此任一时刻最多一个 ACTIVE（API-INV-005）。
 * 校验失败时不写入任何数据。已存在版本的内容从不改写（01 §28）；不含外部调用。V0.1 没有公开的策略管理 API。
 */
@Service
public class RecoveryPolicyActivationService {

    /** 与 recovery_policy 的库内约束一致（V006）。 */
    private static final Pattern POLICY_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private static final int NAME_MAX = 128;

    private final RecoveryPolicyRepository policies;
    private final ManagedResourceRepository resources;
    private final RecoveryPolicyValidator validator;
    private final SchemaCodecRegistry codecs;
    private final Clock clock;

    public RecoveryPolicyActivationService(
            RecoveryPolicyRepository policies,
            ManagedResourceRepository resources,
            RecoveryPolicyValidator validator,
            SchemaCodecRegistry codecs,
            Clock clock) {
        this.policies = policies;
        this.resources = resources;
        this.validator = validator;
        this.codecs = codecs;
        this.clock = clock;
    }

    public record ActivateCommand(
            long managedResourceId, String policyKey, String name, RecoveryPolicyCriteriaV1 criteria) {

        public ActivateCommand {
            Objects.requireNonNull(criteria, "criteria");
        }
    }

    /** @param retiredCount 本次退休的旧 ACTIVE 数 */
    public record Activated(long policyId, int versionNo, int retiredCount) {}

    /**
     * @throws ApplicationException 键或名称不合法（REQUEST_VALIDATION_FAILED）、资源不存在（RESOURCE_NOT_FOUND）、
     *     某项 Criterion 当前不能执行（见 {@link RecoveryPolicyValidator}）
     */
    @Transactional
    public Activated activate(ActivateCommand command) {
        if (command.policyKey() == null
                || !POLICY_KEY.matcher(command.policyKey()).matches()) {
            throw invalid("policyKey");
        }
        if (command.name() == null
                || command.name().isBlank()
                || command.name().codePointCount(0, command.name().length()) > NAME_MAX) {
            throw invalid("name");
        }
        // 先取行锁再做任何一致性读，后续校验读到的是锁后的最新配置
        if (!policies.lockResource(command.managedResourceId())) {
            throw new ApplicationException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Managed resource not found",
                    Map.of("managedResourceId", command.managedResourceId()));
        }
        ManagedResource owner = resources.findById(command.managedResourceId()).orElseThrow();
        validator.validate(owner, command.criteria());

        Instant now = clock.instant();
        int retired = policies.retireActive(owner.id(), now);
        int version = policies.maxVersion(owner.id(), command.policyKey()) + 1;
        String payload = codecs.encode(
                RecoveryPolicyCriteriaV1.SCHEMA_NAME, RecoveryPolicyCriteriaV1.SCHEMA_VERSION, command.criteria());
        long id = policies.insertActive(new RecoveryPolicyRepository.NewRecoveryPolicy(
                owner.id(),
                command.policyKey(),
                command.name(),
                version,
                RecoveryPolicyCriteriaV1.SCHEMA_NAME,
                RecoveryPolicyCriteriaV1.SCHEMA_VERSION,
                payload,
                now));
        return new Activated(id, version, retired);
    }

    private static ApplicationException invalid(String field) {
        return new ApplicationException(
                ErrorCode.REQUEST_VALIDATION_FAILED, "Invalid recovery policy", Map.of("field", field));
    }
}
