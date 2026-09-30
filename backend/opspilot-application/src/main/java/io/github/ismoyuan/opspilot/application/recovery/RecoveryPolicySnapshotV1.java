package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.domain.system.binding.RedisResourceBindingV1;
import java.util.List;
import java.util.Objects;

/**
 * recovery.policy.snapshot / 1：批准事务在创建 PENDING Execution 时冻结的完整恢复合同（04 §45、§52、05 §38）。固定策略身份与版本、
 * 期限与样本时效、按执行顺序的 Criteria（能力、受控参数、采样、谓词、required、阈值），以及此刻解析出的目标资源；queue.inspect 项
 * 另存 Binding 配置选定的 consumerGroup（04 §80“快照保存配置选定的组”，运行时组缺失即 UNKNOWN）。执行成功后直接复制此快照创建
 * Verification，不再查询此刻的 ACTIVE 策略；不含任何 AI 提供的数据、凭据或连接端点。
 *
 * @param managedResourceId 策略所挂的资源（即写操作目标）
 */
public record RecoveryPolicySnapshotV1(
        String schemaName,
        int schemaVersion,
        long policyId,
        String policyKey,
        String policyName,
        int policyVersion,
        long managedResourceId,
        int maxDurationSeconds,
        int maxSampleAgeSeconds,
        List<SnapshotCriterion> criteria) {

    public static final String SCHEMA_NAME = "recovery.policy.snapshot";
    public static final int SCHEMA_VERSION = 1;

    public RecoveryPolicySnapshotV1 {
        if (!SCHEMA_NAME.equals(schemaName)) {
            throw RecoveryChecks.invalid("schemaName");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw RecoveryChecks.invalid("schemaVersion");
        }
        RecoveryChecks.required("policyKey", policyKey);
        RecoveryChecks.required("policyName", policyName);
        RecoveryChecks.positive("policyVersion", policyVersion);
        RecoveryChecks.positive("maxDurationSeconds", maxDurationSeconds);
        RecoveryChecks.positive("maxSampleAgeSeconds", maxSampleAgeSeconds);
        RecoveryChecks.required("criteria", criteria);
        if (criteria.isEmpty()) {
            throw RecoveryChecks.invalid("criteria");
        }
        criteria = List.copyOf(criteria);
    }

    /**
     * 一项冻结的 Criterion。
     *
     * @param consumerGroup queue.inspect 项必填（Binding 配置选定的组），其余能力为空
     */
    public record SnapshotCriterion(RecoveryCriterionV1 criterion, long targetResourceId, String consumerGroup) {

        public SnapshotCriterion {
            RecoveryChecks.required("criterion", criterion);
            if (targetResourceId < 1) {
                throw RecoveryChecks.invalid("targetResourceId");
            }
            boolean queue = criterion instanceof RecoveryCriterionV1.QueueInspect;
            if (queue != (consumerGroup != null)) {
                throw RecoveryChecks.invalid("consumerGroup");
            }
        }
    }

    /** 由已选中并按此刻配置复核的策略生成快照。 */
    public static RecoveryPolicySnapshotV1 of(RecoveryPolicySelector.SelectedRecoveryPolicy selected) {
        Objects.requireNonNull(selected, "selected");
        RecoveryPolicyRecord policy = selected.policy();
        RecoveryPolicyCriteriaV1 criteria = selected.criteria();
        List<SnapshotCriterion> frozen = selected.resolved().stream()
                .map(resolved -> new SnapshotCriterion(
                        resolved.criterion(),
                        resolved.target().id(),
                        resolved.criterion() instanceof RecoveryCriterionV1.QueueInspect
                                ? ((RedisResourceBindingV1) resolved.provider().selector()).consumerGroup()
                                : null))
                .toList();
        return new RecoveryPolicySnapshotV1(
                SCHEMA_NAME,
                SCHEMA_VERSION,
                policy.id(),
                policy.policyKey(),
                policy.name(),
                policy.versionNo(),
                policy.managedResourceId(),
                criteria.maxDurationSeconds(),
                criteria.maxSampleAgeSeconds(),
                frozen);
    }
}
