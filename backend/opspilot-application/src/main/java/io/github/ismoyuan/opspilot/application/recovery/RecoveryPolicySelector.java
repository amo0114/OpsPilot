package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 在写操作准入前选择目标资源唯一合法的 ACTIVE RecoveryPolicy（04 §52、§78、08 TASK-067）：没有 ACTIVE 为
 * RECOVERY_POLICY_NOT_FOUND，多条 ACTIVE 为 RECOVERY_POLICY_AMBIGUOUS（不猜测选择，04 §49）；唯一一条按正式 Codec 解码
 * （类型、谓词字段与采样结构），并按此刻配置以 {@link RecoveryPolicyValidator} 复核每项的目标与能力绑定——激活后绑定或资源
 * 可能已变化，此时策略不合法，同样视为没有可用策略（NOT_FOUND，reason=POLICY_NOT_EXECUTABLE）。
 *
 * <p>只读，由调用方在其已持有 Incident 行锁的事务中调用；批准事务（TASK-069）据返回值冻结快照，外部处理的验证（TASK-081）同理。
 */
@Service
public class RecoveryPolicySelector {

    private final RecoveryPolicyRepository policies;
    private final RecoveryPolicyValidator validator;
    private final SchemaCodecRegistry codecs;

    public RecoveryPolicySelector(
            RecoveryPolicyRepository policies, RecoveryPolicyValidator validator, SchemaCodecRegistry codecs) {
        this.policies = policies;
        this.validator = validator;
        this.codecs = codecs;
    }

    /** 选中的策略行及其已解码 Criteria。 */
    public record SelectedRecoveryPolicy(RecoveryPolicyRecord policy, RecoveryPolicyCriteriaV1 criteria) {}

    /**
     * @param target 写操作的目标资源（策略挂在资源上，04 §51）
     * @throws ApplicationException 没有唯一合法的 ACTIVE 策略
     */
    public SelectedRecoveryPolicy select(ManagedResource target) {
        List<RecoveryPolicyRecord> active = policies.findActive(target.id());
        if (active.isEmpty()) {
            throw notFound(target, "NO_ACTIVE_POLICY", Map.of());
        }
        if (active.size() > 1) {
            throw new ApplicationException(
                    ErrorCode.RECOVERY_POLICY_AMBIGUOUS,
                    "More than one active recovery policy",
                    Map.of("targetResourceId", target.id(), "activeCount", active.size()));
        }
        RecoveryPolicyRecord policy = active.getFirst();
        RecoveryPolicyCriteriaV1 criteria = codecs.decode(
                policy.criteriaSchemaName(),
                policy.criteriaSchemaVersion(),
                policy.criteriaPayload(),
                RecoveryPolicyCriteriaV1.class);
        try {
            validator.validate(target, criteria);
        } catch (ApplicationException ex) {
            // 不合格的判据与其原因：check 为校验拒绝码，checkReason 为其原因；reason 固定表示“策略按此刻配置不可执行”
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("policyId", policy.id());
            details.put("check", ex.errorCode().name());
            details.put("criterionKey", ex.details().get("criterionKey"));
            details.put("checkReason", ex.details().get("reason"));
            throw notFound(target, "POLICY_NOT_EXECUTABLE", details);
        }
        return new SelectedRecoveryPolicy(policy, criteria);
    }

    private static ApplicationException notFound(ManagedResource target, String reason, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>(extra);
        details.put("targetResourceId", target.id());
        details.put("reason", reason);
        return new ApplicationException(
                ErrorCode.RECOVERY_POLICY_NOT_FOUND, "No usable recovery policy for the target", details);
    }
}
