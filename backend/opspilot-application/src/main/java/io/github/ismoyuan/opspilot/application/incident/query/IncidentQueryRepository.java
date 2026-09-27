package io.github.ismoyuan.opspilot.application.incident.query;

import java.util.List;
import java.util.Optional;

/** 故障页面的只读 SQL 投影（07 §24）；不修改状态，编号与系统键按字节精确匹配。 */
public interface IncidentQueryRepository {

    long countIncidents(IncidentFilter filter);

    /** 按检测时间倒序，同时间按编号倒序。 */
    List<IncidentSummaryView> findIncidents(IncidentFilter filter, int offset, int limit);

    Optional<IncidentDetailView> findDetail(String incidentKey);
}
