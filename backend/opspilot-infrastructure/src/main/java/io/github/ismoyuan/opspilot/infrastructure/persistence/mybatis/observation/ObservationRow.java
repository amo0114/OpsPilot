package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.observation;

import java.time.LocalDateTime;

/** observation 行；时间为 UTC。 */
record ObservationRow(
        long id,
        long incidentId,
        Long investigationId,
        Long recoveryVerificationId,
        long capabilityInvocationId,
        long managedResourceId,
        String observationKind,
        String schemaName,
        int schemaVersion,
        String payload,
        String summary,
        LocalDateTime observedAt,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        LocalDateTime createdAt) {}
