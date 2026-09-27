package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.diagnosis;

import io.github.ismoyuan.opspilot.application.diagnosis.DiagnosisRepository;
import io.github.ismoyuan.opspilot.domain.diagnosis.Diagnosis;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisDraft;
import io.github.ismoyuan.opspilot.domain.diagnosis.TerminationReason;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisDiagnosisRepository implements DiagnosisRepository {

    private final DiagnosisMapper mapper;

    MyBatisDiagnosisRepository(DiagnosisMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Diagnosis insert(
            long investigationId, int runNo, DiagnosisDraft draft, TerminationReason terminationReason, Instant at) {
        LocalDateTime createdAt = LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        DiagnosisInsert insert = new DiagnosisInsert(
                investigationId,
                runNo,
                draft.conclusionType().name(),
                draft.primaryHypothesisId(),
                draft.summary(),
                draft.impactSummary(),
                terminationReason.name(),
                createdAt);
        mapper.insertNextVersion(insert);
        if (!draft.evidenceIds().isEmpty()) {
            mapper.insertEvidenceRefs(insert.getId(), draft.evidenceIds(), createdAt);
        }
        DiagnosisRow row = mapper.selectById(insert.getId());
        return new Diagnosis(
                row.id(),
                row.investigationId(),
                row.runNo(),
                row.versionNo(),
                DiagnosisConclusionType.valueOf(row.conclusionType()),
                row.primaryHypothesisId(),
                row.summary(),
                row.impactSummary(),
                TerminationReason.valueOf(row.terminationReason()),
                mapper.selectEvidenceIds(row.id()),
                row.createdAt().toInstant(ZoneOffset.UTC));
    }
}
