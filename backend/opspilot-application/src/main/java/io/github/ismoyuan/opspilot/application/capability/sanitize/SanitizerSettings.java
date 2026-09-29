package io.github.ismoyuan.opspilot.application.capability.sanitize;

/**
 * Sanitizer 的可配置项（06 §54：邮箱等可识别个人信息视配置脱敏；凭据与 Token 始终强制清理，不可关闭）。
 *
 * @param redactEmails 是否把邮箱地址替换为 {@link Sanitizer#REDACTED}
 */
public record SanitizerSettings(boolean redactEmails) {}
