package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.capability.ProviderBinding;
import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * ACTIVE 之前的恢复策略合法性校验（08 TASK-076）。结构、criterionKey 唯一、非空且至少一项 required、谓词与结果字段相容已由
 * {@link RecoveryPolicyCriteriaV1} 构造保证；这里逐项核对依赖配置的部分：目标资源属于策略所在资源的同一 ManagedSystem，
 * 并经与调查相同的 {@link CapabilityAccess} 判定——能力已注册且为 OBSERVE、资源 ACTIVE 且类型受支持、CapabilityBinding 已启用、
 * 唯一 Provider（queue.inspect 的 Binding 必须指定 Stream 与 consumerGroup）；metrics.query 的 metricKey 须在 Binding 中声明。
 *
 * <p>只读，由调用方在其事务中调用；首个不合格项即拒绝，details 只含 criterionKey 与原因，不回显配置内容。通过时按顺序返回每项
 * 解析出的目标资源与唯一 Provider Binding，供批准事务冻结快照（TASK-069）。
 */
@Service
public class RecoveryPolicyValidator {

    private final ManagedResourceRepository resources;
    private final CapabilityAccess access;

    public RecoveryPolicyValidator(ManagedResourceRepository resources, CapabilityAccess access) {
        this.resources = resources;
        this.access = access;
    }

    /** 一项 Criterion 按此刻配置解析出的目标资源与唯一 Provider Binding。 */
    public record ResolvedCriterion(RecoveryCriterionV1 criterion, ManagedResource target, ProviderBinding provider) {}

    /**
     * @param owner 策略所挂的资源
     * @return 与 Criteria 同序的解析结果
     * @throws ApplicationException 某项 Criterion 当前不能执行
     */
    public List<ResolvedCriterion> validate(ManagedResource owner, RecoveryPolicyCriteriaV1 criteria) {
        List<ResolvedCriterion> resolved = new ArrayList<>();
        for (RecoveryCriterionV1 criterion : criteria.criteria()) {
            Optional<ManagedResource> target =
                    resources.findBySystemIdAndResourceKey(owner.managedSystemId(), criterion.targetResourceKey());
            if (target.isEmpty()) {
                throw rejected(ErrorCode.RESOURCE_NOT_IN_SYSTEM, criterion, "TARGET_NOT_IN_SYSTEM");
            }
            switch (access.evaluate(
                    owner.managedSystemId(), target, criterion.capabilityKey().key())) {
                case CapabilityAccess.Denied denied -> throw rejected(denied.code(), criterion, denied.reason());
                case CapabilityAccess.Allowed allowed -> {
                    if (criterion.arguments() instanceof MetricsQueryArgumentsV1 metrics
                            && !((PrometheusResourceBindingV1)
                                            allowed.provider().selector())
                                    .metrics()
                                    .containsKey(metrics.metricKey())) {
                        throw rejected(ErrorCode.CAPABILITY_ARGUMENT_INVALID, criterion, "METRIC_KEY_NOT_AVAILABLE");
                    }
                    resolved.add(new ResolvedCriterion(criterion, allowed.resource(), allowed.provider()));
                }
            }
        }
        return List.copyOf(resolved);
    }

    private static ApplicationException rejected(ErrorCode code, RecoveryCriterionV1 criterion, String reason) {
        return new ApplicationException(
                code,
                "Recovery criterion cannot be executed",
                Map.of("criterionKey", criterion.criterionKey(), "reason", reason == null ? "UNSPECIFIED" : reason));
    }
}
