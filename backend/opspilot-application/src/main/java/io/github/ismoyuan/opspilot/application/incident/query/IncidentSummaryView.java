package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.time.Instant;

/** 故障列表项（05 §22）。 */
public record IncidentSummaryView(
        String incidentKey,
        String title,
        String systemName,
        IncidentStatus status,
        String impactSummary,
        Instant detectedAt,
        Instant updatedAt) {}
