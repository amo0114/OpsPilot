package io.github.ismoyuan.opspilot.application.investigation.context;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityDescriptor;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepRequest;
import java.util.List;
import java.util.Objects;

/**
 * 一次调查决策所需的上下文快照（08 TASK-037、05 §77、02 §15），在准入事务之外读取。它只描述准备时的状态，
 * 不能替代准入 Guard 的最后校验（07 §41）；stepId 与 correlationId 在准入登记 Step 之后才补上。
 *
 * @param currentDiagnosis 该 Investigation 最新的冻结 Diagnosis，没有时为空
 */
public record InvestigationStepContext(
        long incidentId,
        long investigationId,
        int runNo,
        InvestigationStepRequest.Incident incident,
        List<InvestigationStepRequest.AffectedResource> affectedResources,
        List<InvestigationStepRequest.Hypothesis> hypotheses,
        List<InvestigationStepRequest.Observation> observations,
        List<InvestigationStepRequest.Evidence> evidence,
        InvestigationStepRequest.CurrentDiagnosis currentDiagnosis,
        List<CapabilityDescriptor> availableCapabilities,
        InvestigationStepRequest.Budget budget,
        List<InvestigationStepRequest.TimelineEntry> recentTimeline) {

    public InvestigationStepContext {
        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(budget, "budget");
        affectedResources = List.copyOf(affectedResources);
        hypotheses = List.copyOf(hypotheses);
        observations = List.copyOf(observations);
        evidence = List.copyOf(evidence);
        availableCapabilities = List.copyOf(availableCapabilities);
        recentTimeline = List.copyOf(recentTimeline);
    }

    /** 以准入登记的 Step 身份组装协议请求；run 与 investigationId 来自 Java 的准备时快照，Python 只回显（BND-015）。 */
    public InvestigationStepRequest toRequest(long stepId, String correlationId) {
        return new InvestigationStepRequest(
                1,
                investigationId,
                runNo,
                stepId,
                correlationId,
                incident,
                affectedResources,
                hypotheses,
                observations,
                evidence,
                currentDiagnosis,
                availableCapabilities,
                budget,
                recentTimeline);
    }
}
