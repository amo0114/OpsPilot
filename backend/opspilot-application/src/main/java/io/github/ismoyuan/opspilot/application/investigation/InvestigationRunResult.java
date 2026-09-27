package io.github.ismoyuan.opspilot.application.investigation;

import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;

/**
 * Start/Continue 已被接受并落账（05 §24、§28）；不表示后台 Worker 已开始执行。
 *
 * @param version Incident 版本
 */
public record InvestigationRunResult(
        IncidentKey incidentKey, IncidentStatus status, long version, int runNo, boolean stopRequested) {}
