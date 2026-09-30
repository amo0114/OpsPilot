package io.github.ismoyuan.opspilot.application.recovery;

/** RecoveryVerification 的整体结果（01 §30、06 §116）；优先级 FAILED > INCONCLUSIVE > PASSED。 */
public enum RecoveryOutcome {
    PASSED,
    FAILED,
    INCONCLUSIVE
}
