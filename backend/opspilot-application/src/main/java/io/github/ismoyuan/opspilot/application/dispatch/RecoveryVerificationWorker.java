package io.github.ismoyuan.opspilot.application.dispatch;

/** RecoveryVerification Worker 入口（07 §68～§69）；真实实现属 TASK-079/083。 */
public interface RecoveryVerificationWorker {

    void runRecoveryVerification(long verificationId);
}
