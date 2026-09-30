package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import io.github.ismoyuan.opspilot.application.system.query.ActiveRecoveryPolicyProjection;
import io.github.ismoyuan.opspilot.application.system.query.ResourceSummaryView;
import io.github.ismoyuan.opspilot.application.system.query.SystemSummaryView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface SystemQueryMapper {

    long countSystems();

    List<SystemSummaryView> selectSystems(@Param("offset") int offset, @Param("limit") int limit);

    SystemDetailRow selectSystemByKey(@Param("systemKey") String systemKey);

    List<ResourceSummaryView> selectResourcesBySystemId(@Param("managedSystemId") long managedSystemId);

    ResourceIdentityRow selectResourceByKeys(
            @Param("systemKey") String systemKey, @Param("resourceKey") String resourceKey);

    List<String> selectEnabledCapabilityKeys(@Param("managedResourceId") long managedResourceId);

    List<ActiveRecoveryPolicyProjection> selectActiveRecoveryPolicies(
            @Param("managedResourceId") long managedResourceId);
}
