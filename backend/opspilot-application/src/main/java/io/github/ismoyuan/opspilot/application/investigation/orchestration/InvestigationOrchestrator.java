package io.github.ismoyuan.opspilot.application.investigation.orchestration;

import io.github.ismoyuan.opspilot.application.ai.AiDecisionPort;
import io.github.ismoyuan.opspilot.application.ai.InvestigationStepDecision;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import io.github.ismoyuan.opspilot.application.correlation.Correlation;
import io.github.ismoyuan.opspilot.application.dispatch.InvestigationWorker;
import io.github.ismoyuan.opspilot.application.investigation.context.InvestigationContextBuilder;
import io.github.ismoyuan.opspilot.application.investigation.context.InvestigationStepContext;
import io.github.ismoyuan.opspilot.application.investigation.step.AgentStepRecorder;
import io.github.ismoyuan.opspilot.application.investigation.step.StepAdmission;
import io.github.ismoyuan.opspilot.application.investigation.step.StepAdmissionService;
import io.github.ismoyuan.opspilot.application.investigation.step.StepDecisionOutcome;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 调查外层循环（07 §40～§41、02 §14、BND-012）：Java 驱动，本类不开事务，每一步由短事务与事务外的网络调用交替组成：
 *
 * <ol>
 *   <li>事务外准备上下文（不代替最后准入校验）；
 *   <li>准入短事务：持锁检查并登记 RUNNING Step（TASK-039）；
 *   <li>提交后调用 AI，等待不超过 min(单步超时, 本轮剩余时间)，不透明重试；
 *   <li>结果短事务：记录 Step，并按 run/Stop 规则处置一个主 Intent（TASK-040～041）；
 *   <li>REQUEST_CAPABILITY 在结果提交后经 Capability 准入（当前为 Fake Gate）；
 *   <li>下一步，直到准入拒绝、结果不再属于当前 run、同轮已 Stop 或 Diagnosis 已形成。
 * </ol>
 *
 * 只推进给定 run：run 已切换、Incident 已不在调查时直接退出。本轮退出条件（Stop、到期、额度、连续失败）下的确定性收束属 TASK-042，
 * 启动恢复属 TASK-043；此前准入拒绝后本 Worker 只退出。
 */
@Service
public class InvestigationOrchestrator implements InvestigationWorker {

    private static final Logger log = LoggerFactory.getLogger(InvestigationOrchestrator.class);

    private final InvestigationContextBuilder contexts;
    private final StepAdmissionService admissions;
    private final AiDecisionPort ai;
    private final AgentStepRecorder recorder;
    private final IntentDispatcher intents;
    private final CapabilityExecutionPort capabilities;

    public InvestigationOrchestrator(
            InvestigationContextBuilder contexts,
            StepAdmissionService admissions,
            AiDecisionPort ai,
            AgentStepRecorder recorder,
            IntentDispatcher intents,
            CapabilityExecutionPort capabilities) {
        this.contexts = contexts;
        this.admissions = admissions;
        this.ai = ai;
        this.recorder = recorder;
        this.intents = intents;
        this.capabilities = capabilities;
    }

    @Override
    public void runInvestigation(long incidentId, int runNo) {
        while (step(incidentId, runNo)) {
            // 继续本 run 的下一步
        }
    }

    /** @return 是否继续下一步 */
    private boolean step(long incidentId, int runNo) {
        Optional<InvestigationStepContext> context = contexts.build(incidentId, runNo);
        if (context.isEmpty()) {
            return false;
        }
        StepAdmission admission = admissions.admit(incidentId, runNo);
        if (admission instanceof StepAdmission.Rejected rejected) {
            log.info(
                    "Investigation step not admitted: incidentId={} runNo={} reason={}",
                    incidentId,
                    runNo,
                    rejected.reason());
            return false;
        }
        StepAdmission.Admitted admitted = (StepAdmission.Admitted) admission;
        long stepId = admitted.step().id();
        long started = System.nanoTime();
        InvestigationStepDecision decision;
        try {
            decision = ai.decideInvestigationStep(
                    context.get().toRequest(stepId, correlationId(stepId)), admitted.maxWait());
        } catch (OpsPilotException ex) {
            recorder.recordFailure(stepId, ex.errorCode(), ex.getMessage(), elapsedMillis(started));
            // 下一次准入按连续失败阈值、截止与 Stop 判断是否继续（02 §28）
            return true;
        } catch (RuntimeException ex) {
            recorder.recordFailure(
                    stepId, ErrorCode.INTERNAL_ERROR, "AI call failed unexpectedly", elapsedMillis(started));
            throw ex;
        }
        StepDecisionOutcome result = recorder.recordDecision(stepId, decision, elapsedMillis(started), intents);
        return switch (result.disposition().outcome()) {
            case NOT_CURRENT, STOPPED -> false;
            case APPLIED -> !(decision.response() instanceof InvestigationStepResponse.CompleteInvestigationStep);
            case REJECTED -> true;
            case ACCEPTED -> {
                InvestigationStepResponse.RequestCapabilityStep request =
                        (InvestigationStepResponse.RequestCapabilityStep) decision.response();
                capabilities.execute(incidentId, runNo, stepId, request.requestCapability());
                yield true;
            }
        };
    }

    private static String correlationId(long stepId) {
        String current = Correlation.currentId();
        return current != null ? current : "step_" + stepId;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
