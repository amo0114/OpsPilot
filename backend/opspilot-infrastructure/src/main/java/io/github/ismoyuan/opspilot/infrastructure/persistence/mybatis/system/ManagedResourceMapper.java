package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface ManagedResourceMapper {

    ManagedResourceRow selectById(@Param("id") long id);

    ManagedResourceRow selectBySystemIdAndResourceKey(
            @Param("managedSystemId") long managedSystemId, @Param("resourceKey") String resourceKey);

    List<ManagedResourceRow> selectAllBySystemId(@Param("managedSystemId") long managedSystemId);
}
