package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.remediation;

import io.github.ismoyuan.opspilot.application.remediation.RemediationContextQuery;
import io.github.ismoyuan.opspilot.domain.diagnosis.DiagnosisConclusionType;
import io.github.ismoyuan.opspilot.domain.evidence.EvidenceRelation;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisRemediationContextQuery implements RemediationContextQuery {

    private final RemediationQueryMapper mapper;

    MyBatisRemediationContextQuery(RemediationQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<LatestDiagnosis> findLatestDiagnosis(long incidentId) {
        return Optional.ofNullable(mapper.selectLatestDiagnosis(incidentId))
                .map(row -> new LatestDiagnosis(
                        row.id(),
                        row.versionNo(),
                        DiagnosisConclusionType.valueOf(row.conclusionType()),
                        row.summary()));
    }

    @Override
    public List<FrozenEvidence> findFrozenEvidence(long diagnosisId) {
        return mapper.selectFrozenEvidence(diagnosisId).stream()
                .map(row -> new FrozenEvidence(
                        row.id(), EvidenceRelation.valueOf(row.relation()), row.observationSummary(), row.resourceId()))
                .toList();
    }

    @Override
    public List<Long> findAffectedResourceIds(long incidentId) {
        return mapper.selectAffectedResourceIds(incidentId);
    }
}
