package io.github.ismoyuan.opspilot.application.dispatch;

import java.util.Objects;

/**
 * 单飞保护的身份：工作类型＋业务 ID（调查为 incidentId，另两类为 executionId/verificationId，07 §48、§51）。
 */
public record WorkKey(WorkType type, long businessId) {

    public WorkKey {
        Objects.requireNonNull(type, "type");
    }
}
