package io.github.ismoyuan.opspilot.domain.error;

/** 错误语义类别；HTTP 状态只由 web 层按 05 §94 映射，domain 不认识 HTTP。 */
public enum ErrorCategory {
    INVALID_REQUEST,
    NOT_FOUND,
    CONFLICT,
    RULE_VIOLATION,
    DEPENDENCY_UNAVAILABLE,
    DEPENDENCY_INVALID_RESPONSE,
    DEPENDENCY_TIMEOUT,
    INTERNAL
}
