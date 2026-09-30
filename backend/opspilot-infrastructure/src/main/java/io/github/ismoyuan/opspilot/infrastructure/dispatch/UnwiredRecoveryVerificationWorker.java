package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.RecoveryVerificationWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文中没有真实 Verification Worker（{@code RecoveryVerificationService}，08 TASK-079）时的替身，仅用于只装配基础设施的切片测试；
 * 正式应用总有真实 Worker。不改变任何状态、不采样，Verification 保持原状。
 */
class UnwiredRecoveryVerificationWorker implements RecoveryVerificationWorker {

    private static final Logger log = LoggerFactory.getLogger(UnwiredRecoveryVerificationWorker.class);

    @Override
    public void runRecoveryVerification(long verificationId) {
        log.debug("No recovery verification worker in this context; verification unchanged: {}", verificationId);
    }
}
