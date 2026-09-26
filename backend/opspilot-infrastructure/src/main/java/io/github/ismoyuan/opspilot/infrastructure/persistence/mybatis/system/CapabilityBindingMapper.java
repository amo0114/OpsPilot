package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface CapabilityBindingMapper {

    CapabilityBindingRow selectByResourceIdAndCapabilityKey(
            @Param("managedResourceId") long managedResourceId, @Param("capabilityKey") String capabilityKey);

    List<CapabilityBindingRow> selectAllByResourceId(@Param("managedResourceId") long managedResourceId);
}
