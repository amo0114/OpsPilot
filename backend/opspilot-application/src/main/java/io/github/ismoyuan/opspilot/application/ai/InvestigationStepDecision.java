package io.github.ismoyuan.opspilot.application.ai;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import java.util.Objects;

/** 通过协议校验的单步结果及其调用元数据。 */
public record InvestigationStepDecision(InvestigationStepResponse response, AiCallMetadata metadata) {

    public InvestigationStepDecision {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(metadata, "metadata");
    }
}
