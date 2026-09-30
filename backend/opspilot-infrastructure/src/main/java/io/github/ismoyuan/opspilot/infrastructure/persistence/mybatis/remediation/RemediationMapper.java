package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.remediation;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 方案与审批的条件更新：只从 ACTIVE / PENDING 出发，已决定或已结束的记录不被改写（04 §38、§44）。 */
@Mapper
interface RemediationMapper {

    int supersedeActivePlans(@Param("incidentId") long incidentId, @Param("at") LocalDateTime at);

    int cancelActivePlans(@Param("incidentId") long incidentId, @Param("at") LocalDateTime at);

    int cancelPendingApprovals(
            @Param("incidentId") long incidentId, @Param("at") LocalDateTime at, @Param("actor") String actor);

    boolean existsPendingApproval(@Param("incidentId") long incidentId);
}
