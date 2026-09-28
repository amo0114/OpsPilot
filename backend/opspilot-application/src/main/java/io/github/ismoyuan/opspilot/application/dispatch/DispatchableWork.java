package io.github.ismoyuan.opspilot.application.dispatch;

/**
 * 一次进程内唤醒的内容：调查携带期望 runNo，Execution 与 Verification 携带持久化身份（07 §45）。
 * 只是唤醒意图，工作事实始终在数据库。
 */
public sealed interface DispatchableWork {

    WorkKey key();

    /** @param runNo Worker 必须以此 run 做准入校验，不能自取当前 run */
    record Investigation(long incidentId, int runNo) implements DispatchableWork {

        public Investigation {
            if (runNo < 1) {
                throw new IllegalArgumentException("runNo must be >= 1");
            }
        }

        @Override
        public WorkKey key() {
            return new WorkKey(WorkType.INVESTIGATION, incidentId);
        }
    }

    record ActionExecution(long executionId) implements DispatchableWork {

        @Override
        public WorkKey key() {
            return new WorkKey(WorkType.ACTION_EXECUTION, executionId);
        }
    }

    record RecoveryVerification(long verificationId) implements DispatchableWork {

        @Override
        public WorkKey key() {
            return new WorkKey(WorkType.RECOVERY_VERIFICATION, verificationId);
        }
    }
}
