package io.github.ismoyuan.opspilot.web.error;

import java.util.Map;

/** 05 §9 标准错误响应。 */
public record ErrorResponse(String code, String message, String requestId, Map<String, Object> details) {}
