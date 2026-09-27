package io.github.ismoyuan.opspilot.web.incident;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 05 §33 请求体。
 *
 * @param reason 可为空
 */
public record CancelIncidentRequest(@NotNull @PositiveOrZero Long expectedVersion, String reason) {}
