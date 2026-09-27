package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * AI Runtime → Java 的单步结果（05 §78～§86）：按 intentType 判别，恰有一个与之对应的主 payload，其他 payload 或未定义字段
 * 一律拒绝（BND-013）。protocolVersion、runNo、stepId 只是回显，Java 与已登记 Step 比对，不据此授权（BND-015）。
 * 协议只校验结构；runNo 是否当前、引用是否存在与归属由 Java 业务事务判定（TASK-040）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "intentType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = InvestigationStepResponse.RequestCapabilityStep.class, name = "REQUEST_CAPABILITY"),
    @JsonSubTypes.Type(value = InvestigationStepResponse.ProposeHypothesisStep.class, name = "PROPOSE_HYPOTHESIS"),
    @JsonSubTypes.Type(value = InvestigationStepResponse.UpdateHypothesisStep.class, name = "UPDATE_HYPOTHESIS"),
    @JsonSubTypes.Type(value = InvestigationStepResponse.ProposeEvidenceLinkStep.class, name = "PROPOSE_EVIDENCE_LINK"),
    @JsonSubTypes.Type(
            value = InvestigationStepResponse.CompleteInvestigationStep.class,
            name = "COMPLETE_INVESTIGATION")
})
public sealed interface InvestigationStepResponse {

    int protocolVersion();

    int runNo();

    long stepId();

    @JsonIgnore
    InvestigationIntentType intentType();

    record RequestCapabilityStep(int protocolVersion, int runNo, long stepId, RequestCapability requestCapability)
            implements InvestigationStepResponse {

        public RequestCapabilityStep {
            echo(protocolVersion, runNo, stepId);
            ProtocolChecks.required("requestCapability", requestCapability);
        }

        @Override
        public InvestigationIntentType intentType() {
            return InvestigationIntentType.REQUEST_CAPABILITY;
        }
    }

    record ProposeHypothesisStep(int protocolVersion, int runNo, long stepId, ProposeHypothesis proposeHypothesis)
            implements InvestigationStepResponse {

        public ProposeHypothesisStep {
            echo(protocolVersion, runNo, stepId);
            ProtocolChecks.required("proposeHypothesis", proposeHypothesis);
        }

        @Override
        public InvestigationIntentType intentType() {
            return InvestigationIntentType.PROPOSE_HYPOTHESIS;
        }
    }

    record UpdateHypothesisStep(int protocolVersion, int runNo, long stepId, UpdateHypothesis updateHypothesis)
            implements InvestigationStepResponse {

        public UpdateHypothesisStep {
            echo(protocolVersion, runNo, stepId);
            ProtocolChecks.required("updateHypothesis", updateHypothesis);
        }

        @Override
        public InvestigationIntentType intentType() {
            return InvestigationIntentType.UPDATE_HYPOTHESIS;
        }
    }

    /**
     * 唯一允许附带状态更新的 Intent（05 §82、BND-013）：更新只能针对同一 Hypothesis。
     *
     * @param hypothesisUpdate 可为空
     */
    record ProposeEvidenceLinkStep(
            int protocolVersion,
            int runNo,
            long stepId,
            ProposeEvidenceLink proposeEvidenceLink,
            HypothesisUpdate hypothesisUpdate)
            implements InvestigationStepResponse {

        public ProposeEvidenceLinkStep {
            echo(protocolVersion, runNo, stepId);
            ProtocolChecks.required("proposeEvidenceLink", proposeEvidenceLink);
            // JSON Schema 无法表达的跨字段规则，两端类型模型都必须拒绝
            if (hypothesisUpdate != null && hypothesisUpdate.hypothesisId() != proposeEvidenceLink.hypothesisId()) {
                throw ProtocolChecks.invalid("hypothesisUpdate.hypothesisId");
            }
        }

        @Override
        public InvestigationIntentType intentType() {
            return InvestigationIntentType.PROPOSE_EVIDENCE_LINK;
        }
    }

    record CompleteInvestigationStep(
            int protocolVersion, int runNo, long stepId, CompleteInvestigation completeInvestigation)
            implements InvestigationStepResponse {

        public CompleteInvestigationStep {
            echo(protocolVersion, runNo, stepId);
            ProtocolChecks.required("completeInvestigation", completeInvestigation);
        }

        @Override
        public InvestigationIntentType intentType() {
            return InvestigationIntentType.COMPLETE_INVESTIGATION;
        }
    }

    private static void echo(int protocolVersion, int runNo, long stepId) {
        ProtocolChecks.protocolVersion(protocolVersion);
        ProtocolChecks.positive("runNo", runNo);
        ProtocolChecks.id("stepId", stepId);
    }
}
