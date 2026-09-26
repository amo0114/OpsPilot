package io.github.ismoyuan.opspilot.web.response;

/** 05 §8 单对象成功响应。 */
public record ApiResponse<T>(T data, String requestId) {}
