package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/** COMPLETE_INVESTIGATION 主 payload（05 §85）。 */
public record CompleteInvestigation(DiagnosisDraftV1 diagnosis) {

    public CompleteInvestigation {
        ProtocolChecks.required("diagnosis", diagnosis);
    }
}
