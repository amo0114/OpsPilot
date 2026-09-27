package io.github.ismoyuan.opspilot.application.investigation.query;

import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import java.time.Instant;

/** 05 §55。 */
public record DiagnosisSummaryView(
        int version, DiagnosisConclusionType conclusionType, String summary, Instant createdAt) {}
