package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;

/**
 * 故障列表筛选（05 §22）。
 *
 * @param systemKey 可为空；按字节精确匹配
 * @param status 可为空
 */
public record IncidentFilter(String systemKey, IncidentStatus status) {}
