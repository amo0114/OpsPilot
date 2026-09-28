package io.github.ismoyuan.opspilot.application.investigation.step;

import java.util.Objects;

/** 合法输出记录后的当前情况与处置。 */
public record StepDecisionOutcome(StepOutcome outcome, IntentDisposition disposition) {

    public StepDecisionOutcome {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(disposition, "disposition");
    }
}
