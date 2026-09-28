package io.github.ismoyuan.opspilot.infrastructure.dispatch;

import io.github.ismoyuan.opspilot.application.dispatch.RecoveryVerificationWorker;

/** RecoveryVerification 表与 Worker 建立前的占位（TASK-074/079 替换并删除本类）；当前没有任何来源会派发验证工作。 */
class PlaceholderRecoveryVerificationWorker implements RecoveryVerificationWorker {

    @Override
    public void runRecoveryVerification(long verificationId) {
        throw new IllegalStateException(
                "Recovery verification worker not available before TASK-079: " + verificationId);
    }
}
