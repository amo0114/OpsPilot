package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/**
 * PROPOSE_HYPOTHESIS 主 payload（05 §81）；上限与 hypothesis 列一致。
 *
 * @param description 可为空
 */
public record ProposeHypothesis(String title, String description) {

    public ProposeHypothesis {
        ProtocolChecks.text("title", title, 200);
        ProtocolChecks.optionalText("description", description, 2000);
    }
}
