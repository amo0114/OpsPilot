package io.github.ismoyuan.opspilot.application.investigation.query;

import java.time.Instant;

/**
 * 概览所需的持久事实（05 §50）；预算与耗时由查询服务派生。
 *
 * @param currentRunDiagnosedAt 当前 run 已产生 Diagnosis 时其创建时间（本轮收束时刻），否则为空
 */
public record InvestigationFacts(
        int runNo,
        Instant startedAt,
        Instant currentRunStartedAt,
        Instant lastActivityAt,
        boolean stopRequested,
        int currentRunCapabilityCount,
        int maxCapabilityCalls,
        int maxDurationSeconds,
        long totalCapabilityCalls,
        long hypothesisCount,
        long observationCount,
        long evidenceCount,
        long diagnosisVersions,
        Instant currentRunDiagnosedAt) {}
