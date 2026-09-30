package io.github.ismoyuan.opspilot.domain.recovery;

/** RecoveryVerification 状态（01 §29、04 §50）；终态不可覆盖，重新验证新建记录。 */
public enum RecoveryVerificationStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    INCONCLUSIVE;

    public boolean terminal() {
        return this == PASSED || this == FAILED || this == INCONCLUSIVE;
    }
}
