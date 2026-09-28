package io.github.ismoyuan.opspilot.application.investigation.step;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.InvestigationStepResponse;
import java.util.Objects;

/**
 * agent_step_record.output_payload 的内容（output_schema_version=1）：AI 的结构化提议原文与 Java 的处置（04 §59）。
 * 不含 Prompt、上下文或思维链（07 §79）。
 */
public record AgentStepOutput(InvestigationStepResponse response, IntentDisposition disposition) {

    public static final int SCHEMA_VERSION = 1;

    public AgentStepOutput {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(disposition, "disposition");
    }
}
