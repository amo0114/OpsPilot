package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import io.github.ismoyuan.opspilot.application.system.DataSourceConnectionRepository;
import io.github.ismoyuan.opspilot.domain.system.ConfigSchema;
import io.github.ismoyuan.opspilot.domain.system.ConnectionStatus;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisDataSourceConnectionRepository implements DataSourceConnectionRepository {

    private final DataSourceConnectionMapper mapper;

    MyBatisDataSourceConnectionRepository(DataSourceConnectionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<DataSourceConnection> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisDataSourceConnectionRepository::toDomain);
    }

    @Override
    public Optional<DataSourceConnection> findByConnectionKey(String connectionKey) {
        return Optional.ofNullable(mapper.selectByConnectionKey(connectionKey))
                .map(MyBatisDataSourceConnectionRepository::toDomain);
    }

    private static DataSourceConnection toDomain(DataSourceConnectionRow row) {
        return new DataSourceConnection(
                row.id(),
                row.connectionKey(),
                row.name(),
                ProviderType.valueOf(row.providerType()),
                row.endpoint(),
                row.credentialRef(),
                new ConfigSchema(row.configSchemaName(), row.configSchemaVersion()),
                row.configPayload(),
                ConnectionStatus.valueOf(row.status()),
                row.lockVersion());
    }
}
