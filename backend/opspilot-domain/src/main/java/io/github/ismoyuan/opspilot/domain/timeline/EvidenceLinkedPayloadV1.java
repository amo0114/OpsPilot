package io.github.ismoyuan.opspilot.domain.timeline;

import java.util.Objects;

/** EVIDENCE_LINKED 载荷：timeline.evidence-linked / 1。 */
public record EvidenceLinkedPayloadV1(
        String incidentKey,
        long investigationId,
        long evidenceId,
        long observationId,
        long hypothesisId,
        String relation)
        implements TimelinePayload {

    public static final String SCHEMA_NAME = "timeline.evidence-linked";
    public static final int SCHEMA_VERSION = 1;

    public EvidenceLinkedPayloadV1 {
        Objects.requireNonNull(incidentKey, "incidentKey");
        Objects.requireNonNull(relation, "relation");
    }

    @Override
    public String schemaName() {
        return SCHEMA_NAME;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }
}
