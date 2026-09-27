package io.github.ismoyuan.opspilot.application.investigation.query;

import io.github.ismoyuan.opspilot.domain.observation.ObservationKind;
import java.time.Instant;

/** 05 §52：列表不含 payload 与原始结果。 */
public record ObservationSummaryView(
        long id,
        ObservationKind kind,
        ResourceRefView resource,
        String summary,
        Instant observedAt,
        long capabilityInvocationId) {}
