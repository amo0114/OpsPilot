package io.github.ismoyuan.opspilot.application.investigation.query;

import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;

/**
 * 05 §52 过滤条件，均可为空。
 *
 * @param resourceKey 按字节精确匹配
 */
public record ObservationFilter(String resourceKey, ObservationKind kind) {}
