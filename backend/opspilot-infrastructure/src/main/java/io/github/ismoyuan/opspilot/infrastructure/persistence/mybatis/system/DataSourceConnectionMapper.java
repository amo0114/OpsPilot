package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface DataSourceConnectionMapper {

    DataSourceConnectionRow selectById(@Param("id") long id);

    DataSourceConnectionRow selectByConnectionKey(@Param("connectionKey") String connectionKey);
}
