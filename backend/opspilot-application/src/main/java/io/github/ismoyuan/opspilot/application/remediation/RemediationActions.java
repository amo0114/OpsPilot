package io.github.ismoyuan.opspilot.application.remediation;

import io.github.ismoyuan.opspilot.application.capability.CapabilityAccess;
import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityDefinition;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityMode;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * 计算某次诊断可提供给 AI 的写动作（08 TASK-063、06 §102～§107）。候选资源只取与本次诊断相关的：当前 Diagnosis 冻结 Evidence 所依据的
 * 资源与 Incident 登记的受影响资源（用户决定，2026-09-30）——系统里其他可重启的服务不会出现，AI 无法被迫为无关组件提出重启。每个候选
 * 资源对每个 CHANGE 能力经 {@link CapabilityAccess#evaluateChange} 与调查相同的归属、ACTIVE、类型、绑定与唯一 Provider 判定。
 * 请求 AI 之前与创建方案的事务内（TASK-065）用同一计算，防止两处漂移。
 */
@Service
public class RemediationActions {

    private final CapabilityRegistry registry;
    private final CapabilityAccess access;
    private final ManagedResourceRepository resources;
    private final RemediationContextQuery query;

    public RemediationActions(
            CapabilityRegistry registry,
            CapabilityAccess access,
            ManagedResourceRepository resources,
            RemediationContextQuery query) {
        this.registry = registry;
        this.access = access;
        this.resources = resources;
        this.query = query;
    }

    /** 与该 Diagnosis 相关的候选资源（冻结 Evidence 所依据的资源 ∪ Incident 受影响资源）上的可用写动作。 */
    public List<AllowedRemediationAction> allowedActionsForDiagnosis(
            long managedSystemId, long incidentId, List<RemediationContextQuery.FrozenEvidence> frozenEvidence) {
        Set<Long> candidates = new LinkedHashSet<>();
        frozenEvidence.forEach(e -> candidates.add(e.resourceId()));
        candidates.addAll(query.findAffectedResourceIds(incidentId));
        return allowedActions(managedSystemId, candidates);
    }

    /** @return 按资源 id、能力键稳定排序；没有可用动作时为空 */
    public List<AllowedRemediationAction> allowedActions(long managedSystemId, Collection<Long> candidateResourceIds) {
        List<CapabilityDefinition> changes = registry.all().stream()
                .filter(definition -> definition.mode() == CapabilityMode.CHANGE)
                .toList();
        List<AllowedRemediationAction> allowed = new ArrayList<>();
        for (long resourceId : new TreeSet<>(candidateResourceIds)) {
            var resource = resources.findById(resourceId);
            for (CapabilityDefinition definition : changes) {
                if (access.evaluateChange(
                                managedSystemId, resource, definition.key().key())
                        instanceof CapabilityAccess.Allowed ok) {
                    allowed.add(new AllowedRemediationAction(ok.definition(), ok.resource()));
                }
            }
        }
        return List.copyOf(allowed);
    }
}
