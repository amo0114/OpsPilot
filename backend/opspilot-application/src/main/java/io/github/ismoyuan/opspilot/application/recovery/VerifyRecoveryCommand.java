package io.github.ismoyuan.opspilot.application.recovery;

/**
 * 外部处理后请求恢复验证（05 §34）。
 *
 * @param resourceKey 用户处理的资源（本 Incident 所属系统内）
 * @param note 可为空
 */
public record VerifyRecoveryCommand(
        String incidentKey, long expectedVersion, String resourceKey, String note, String actor) {}
