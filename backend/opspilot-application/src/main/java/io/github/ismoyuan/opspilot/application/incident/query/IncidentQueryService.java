package io.github.ismoyuan.opspilot.application.incident.query;

import io.github.ismoyuan.opspilot.application.incident.IncidentLocks;
import io.github.ismoyuan.opspilot.application.query.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 故障列表与详情读取（05 §22～§23）；只读事务保证同一请求读到一致快照。 */
@Service
public class IncidentQueryService {

    private final IncidentQueryRepository repository;

    public IncidentQueryService(IncidentQueryRepository repository) {
        this.repository = repository;
    }

    /** 分页参数已由 web 边界校验。 */
    @Transactional(readOnly = true)
    public PageResult<IncidentSummaryView> listIncidents(IncidentFilter filter, int page, int size) {
        long total = repository.countIncidents(filter);
        return new PageResult<>(
                repository.findIncidents(filter, Math.multiplyExact(page, size), size), page, size, total);
    }

    @Transactional(readOnly = true)
    public IncidentDetailView getIncident(String incidentKey) {
        return repository.findDetail(incidentKey).orElseThrow(() -> IncidentLocks.notFound(incidentKey));
    }
}
