package io.github.ismoyuan.opspilot.application.recovery;

import io.github.ismoyuan.opspilot.domain.incident.IncidentKey;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import io.github.ismoyuan.opspilot.domain.recovery.RecoveryVerificationStatus;

/** verify-recovery 已提交的结果：新 Verification 的编号与状态（PENDING），以及迁移后的 Incident（05 §34）。 */
public record VerifyRecoveryResult(
        IncidentKey incidentKey,
        IncidentStatus incidentStatus,
        long incidentVersion,
        int verificationNo,
        RecoveryVerificationStatus verificationStatus) {}
