package io.github.ismoyuan.opspilot.application.investigation.orchestration;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.RequestCapability;
import io.github.ismoyuan.opspilot.application.capability.CapabilityExecutionResult;
import io.github.ismoyuan.opspilot.application.capability.CapabilityExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 调查循环中 REQUEST_CAPABILITY 的真实执行（08 TASK-058，替换 B11 的 Fake Gate）：委托 {@link CapabilityExecutionService}——准入短事务
 * （Incident → Investigation 锁序，run/Stop/截止/额度与全部 Guard，登记调用并扣预算）→ 事务外 Provider → 结果短事务（Observation 或错误
 * ＋时间线）。编排器在 Step 结果提交之后调用，本类不开事务；新 Observation 与失败/拒绝反馈在下一步上下文中可见。
 */
@Service
public class InvestigationCapabilityExecutor implements CapabilityExecutionPort {

    private static final Logger log = LoggerFactory.getLogger(InvestigationCapabilityExecutor.class);

    private final CapabilityExecutionService execution;

    public InvestigationCapabilityExecutor(CapabilityExecutionService execution) {
        this.execution = execution;
    }

    @Override
    public int closeOrphanedCalls(long incidentId) {
        return execution.closeOrphanedCalls(incidentId);
    }

    @Override
    public CapabilityRequestResult execute(long incidentId, int runNo, long stepId, RequestCapability request) {
        CapabilityRequestResult result = switch (execution.execute(incidentId, runNo, request)) {
            case CapabilityExecutionResult.NotAdmitted notAdmitted ->
                new CapabilityRequestResult.Rejected(notAdmitted.reason());
            case CapabilityExecutionResult.Rejected rejected ->
                new CapabilityRequestResult.Refused(rejected.code(), rejected.reason());
            case CapabilityExecutionResult.Succeeded succeeded ->
                new CapabilityRequestResult.Executed(succeeded.invocationId(), succeeded.observationIds());
            case CapabilityExecutionResult.Failed failed ->
                new CapabilityRequestResult.Failed(failed.invocationId(), failed.errorCode());
            case CapabilityExecutionResult.Discarded discarded ->
                new CapabilityRequestResult.Discarded(discarded.invocationId());
        };
        log.info(
                "Capability request handled: stepId={} capability={} result={}",
                stepId,
                request.capabilityKey().key(),
                result);
        return result;
    }
}
