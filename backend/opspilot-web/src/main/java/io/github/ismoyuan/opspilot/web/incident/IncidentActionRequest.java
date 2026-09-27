package io.github.ismoyuan.opspilot.web.incident;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** start/continue/stop 请求体（05 §11、§24、§27、§28）。 */
public record IncidentActionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}
