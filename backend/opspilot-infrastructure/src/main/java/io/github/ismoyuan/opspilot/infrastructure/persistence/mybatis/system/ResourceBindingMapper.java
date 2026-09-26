package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface ResourceBindingMapper {

    List<ResourceBindingRow> selectAllByResourceId(@Param("managedResourceId") long managedResourceId);
}
