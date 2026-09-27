package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.incident;

import io.github.ismoyuan.opspilot.application.incident.query.AffectedResourceView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface IncidentQueryMapper {

    long countIncidents(@Param("systemKey") String systemKey, @Param("status") String status);

    List<IncidentSummaryRow> selectIncidents(
            @Param("systemKey") String systemKey,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit);

    IncidentDetailRow selectDetail(@Param("incidentKey") String incidentKey);

    List<AffectedResourceView> selectAffectedResources(@Param("incidentId") long incidentId);
}
