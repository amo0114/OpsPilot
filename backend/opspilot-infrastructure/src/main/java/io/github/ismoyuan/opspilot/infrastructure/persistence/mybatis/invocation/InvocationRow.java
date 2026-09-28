package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.invocation;

import java.time.LocalDateTime;

record InvocationRow(
        long id,
        long incidentId,
        Long investigationId,
        Long recoveryVerificationId,
        Integer runNo,
        String capabilityKey,
        long managedResourceId,
        String status,
        LocalDateTime startedAt) {}
