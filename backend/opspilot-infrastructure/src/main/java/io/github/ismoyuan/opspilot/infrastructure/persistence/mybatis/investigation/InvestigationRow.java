package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import java.time.LocalDateTime;

/** investigation 行；时间为 UTC 的 LocalDateTime。 */
record InvestigationRow(
        long id,
        long incidentId,
        LocalDateTime startedAt,
        LocalDateTime lastActivityAt,
        int currentRunNo,
        LocalDateTime currentRunStartedAt,
        int currentRunCapabilityCount,
        long capabilityCallCount,
        int consecutiveAiFailureCount,
        LocalDateTime stopRequestedAt,
        String stopRequestedBy,
        int maxCapabilityCalls,
        int maxDurationSeconds,
        int agentStepTimeoutSeconds,
        int maxConsecutiveAiFailures,
        long lockVersion) {}
