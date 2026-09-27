package io.github.ismoyuan.opspilot.application.investigation.query;

import java.time.Instant;

/** 调查概览（05 §50）：本轮额度与历史累计分开；预算不是进度。 */
public record InvestigationOverviewView(
        int runNo,
        Instant startedAt,
        Instant currentRunStartedAt,
        Instant lastActivityAt,
        boolean stopRequested,
        RunBudgetView budget,
        long totalCapabilityCalls,
        long hypothesisCount,
        long observationCount,
        long evidenceCount,
        long diagnosisVersions) {

    /**
     * 当前 run 的额度（scope 固定为 ACTIVE_RUN）。
     *
     * @param durationSeconds 本轮已用墙钟秒数：进行中按当前时间，已收束按本轮 Diagnosis 时间，其他结束按 Incident 更新时间；
     *     截在 [0, durationLimitSeconds]
     */
    public record RunBudgetView(
            int capabilityCallsUsed,
            int capabilityCallsLimit,
            int remainingCapabilityCalls,
            long durationSeconds,
            int durationLimitSeconds) {}
}
