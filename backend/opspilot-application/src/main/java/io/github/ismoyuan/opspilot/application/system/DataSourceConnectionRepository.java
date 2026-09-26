package io.github.ismoyuan.opspilot.application.system;

import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import java.util.Optional;

/** 数据源连接读取端口；V0.1 连接配置由 Seed 建立，不提供写入。 */
public interface DataSourceConnectionRepository {

    Optional<DataSourceConnection> findById(long id);

    Optional<DataSourceConnection> findByConnectionKey(String connectionKey);
}
