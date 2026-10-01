package io.github.ismoyuan.opspilot.application.incident.query;

import java.util.List;
import java.util.Optional;

/**
 * 故障页面的只读 SQL 投影（07 §24）；不修改状态，编号与系统键按字节精确匹配。详情各节由调用方在同一只读事务中依次读取。
 */
public interface IncidentQueryRepository {

    long countIncidents(IncidentFilter filter);

    /** 按检测时间倒序，同时间按编号倒序。 */
    List<IncidentSummaryView> findIncidents(IncidentFilter filter, int offset, int limit);

    Optional<IncidentSnapshot> findSnapshot(String incidentKey);

    /** 最新版本的 Diagnosis 及其冻结 SUPPORTS Evidence 的观测摘要。 */
    Optional<CurrentAssessmentView> findCurrentAssessment(long incidentId);

    /** id 最大的 Plan 及其 Action、Approval、Execution。 */
    Optional<RemediationView> findLatestRemediation(long incidentId);
}
