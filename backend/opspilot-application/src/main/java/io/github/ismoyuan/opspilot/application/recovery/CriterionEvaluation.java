package io.github.ismoyuan.opspilot.application.recovery;

import java.util.Objects;

/** 一项检查的三值结果与原因；TRUE ⇔ SATISFIED，FALSE ⇔ VIOLATED。 */
public record CriterionEvaluation(CriterionResult result, CriterionReason reason) {

    public CriterionEvaluation {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(reason, "reason");
        if ((result == CriterionResult.TRUE) != (reason == CriterionReason.SATISFIED)
                || (result == CriterionResult.FALSE) != (reason == CriterionReason.VIOLATED)) {
            throw new IllegalArgumentException("reason does not match result");
        }
    }

    static CriterionEvaluation satisfied() {
        return new CriterionEvaluation(CriterionResult.TRUE, CriterionReason.SATISFIED);
    }

    static CriterionEvaluation violated() {
        return new CriterionEvaluation(CriterionResult.FALSE, CriterionReason.VIOLATED);
    }

    public static CriterionEvaluation unknown(CriterionReason reason) {
        return new CriterionEvaluation(CriterionResult.UNKNOWN, reason);
    }
}
