package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface ManagedSystemMapper {

    ManagedSystemRow selectBySystemKey(@Param("systemKey") String systemKey);
}
