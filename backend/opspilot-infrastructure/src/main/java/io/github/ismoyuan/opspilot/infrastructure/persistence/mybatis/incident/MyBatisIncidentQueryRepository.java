package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import io.github.ismoyuan.opspilot.application.incident.query.IncidentDetailView;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentFilter;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentQueryRepository;
import io.github.ismoyuan.opspilot.application.incident.query.IncidentSummaryView;
import io.github.ismoyuan.opspilot.application.incident.query.InvestigationRunView;
import io.github.ismoyuan.opspilot.domain.incident.IncidentStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisIncidentQueryRepository implements IncidentQueryRepository {

    private final IncidentQueryMapper mapper;

    MyBatisIncidentQueryRepository(IncidentQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long countIncidents(IncidentFilter filter) {
        return mapper.countIncidents(filter.systemKey(), statusName(filter));
    }

    @Override
    public List<IncidentSummaryView> findIncidents(IncidentFilter filter, int offset, int limit) {
        return mapper.selectIncidents(filter.systemKey(), statusName(filter), offset, limit).stream()
                .map(row -> new IncidentSummaryView(
                        row.incidentKey(),
                        row.title(),
                        row.systemName(),
                        IncidentStatus.valueOf(row.status()),
                        row.impactSummary(),
                        instant(row.detectedAt()),
                        instant(row.updatedAt())))
                .toList();
    }

    @Override
    public Optional<IncidentDetailView> findDetail(String incidentKey) {
        return Optional.ofNullable(mapper.selectDetail(incidentKey))
                .map(row -> new IncidentDetailView(
                        row.incidentKey(),
                        row.title(),
                        row.description(),
                        row.systemKey(),
                        row.systemName(),
                        IncidentStatus.valueOf(row.status()),
                        row.lockVersion(),
                        row.impactSummary(),
                        instant(row.startedAt()),
                        instant(row.detectedAt()),
                        instant(row.resolvedAt()),
                        mapper.selectAffectedResources(row.id()),
                        row.runNo() == null
                                ? null
                                : new InvestigationRunView(row.runNo(), row.stopRequestedAt() != null)));
    }

    private static String statusName(IncidentFilter filter) {
        return filter.status() == null ? null : filter.status().name();
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
