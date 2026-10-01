package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.application.incident.IncidentLocks;
import io.github.ismoyuan.opspilot.application.investigation.query.InvestigationOverviewView;
import io.github.ismoyuan.opspilot.application.investigation.query.InvestigationQueryService;
import io.github.ismoyuan.opspilot.application.query.PageResult;
import io.github.ismoyuan.opspilot.application.recovery.RecoveryStatusReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 故障列表与详情读取（05 §22～§23、08 TASK-085～086），全部只读。详情在一个 REPEATABLE READ 只读事务中依次读取 Snapshot 与各节：
 * InnoDB 在第一次一致性读取时建立视图，之后的查询都读同一版本，查询中途其他事务提交的状态、Timeline、Diagnosis、方案或验证不会混入
 * （05 §65、§67）。查询路径不修改任何业务状态（07 §24）。
 */
@Service
public class IncidentQueryService {

    private final IncidentQueryRepository repository;
    private final InvestigationQueryService investigations;
    private final RecoveryStatusReader recoveries;
    private final AvailableActionsResolver actions;

    public IncidentQueryService(
            IncidentQueryRepository repository,
            InvestigationQueryService investigations,
            RecoveryStatusReader recoveries,
            AvailableActionsResolver actions) {
        this.repository = repository;
        this.investigations = investigations;
        this.recoveries = recoveries;
        this.actions = actions;
    }

    /** 分页参数已由 web 边界校验。 */
    @Transactional(readOnly = true)
    public PageResult<IncidentSummaryView> listIncidents(IncidentFilter filter, int page, int size) {
        long total = repository.countIncidents(filter);
        return new PageResult<>(
                repository.findIncidents(filter, Math.multiplyExact(page, size), size), page, size, total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IncidentDetailView getIncident(String incidentKey) {
        IncidentSnapshot incident =
                repository.findSnapshot(incidentKey).orElseThrow(() -> IncidentLocks.notFound(incidentKey));
        long incidentId = incident.incidentId();
        return new IncidentDetailView(
                incident.incidentKey(),
                incident.title(),
                incident.description(),
                incident.systemKey(),
                incident.systemName(),
                incident.status(),
                incident.version(),
                incident.lastTimelineEventId(),
                incident.impactSummary(),
                incident.startedAt(),
                incident.detectedAt(),
                incident.resolvedAt(),
                incident.affectedResources(),
                incident.runNo() == null ? null : investigation(incidentKey),
                repository.findCurrentAssessment(incidentId).orElse(null),
                repository.findLatestRemediation(incidentId).orElse(null),
                recoveries.latest(incidentId).orElse(null),
                actions.resolve(incident));
    }

    /** 本轮额度与历史累计沿用调查概览的同一计算（05 §50），在本事务内读取。 */
    private IncidentDetailView.Investigation investigation(String incidentKey) {
        InvestigationOverviewView overview = investigations.getOverview(incidentKey);
        return new IncidentDetailView.Investigation(
                overview.runNo(),
                overview.stopRequested(),
                overview.startedAt(),
                overview.currentRunStartedAt(),
                overview.budget(),
                overview.totalCapabilityCalls());
    }
}
