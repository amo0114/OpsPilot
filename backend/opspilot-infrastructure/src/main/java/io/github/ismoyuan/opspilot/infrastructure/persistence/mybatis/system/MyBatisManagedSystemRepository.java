package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import io.github.ismoyuan.opspilot.application.system.ManagedSystemRepository;
import io.github.ismoyuan.opspilot.domain.system.ManagedSystem;
import io.github.ismoyuan.opspilot.domain.system.SystemStatus;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisManagedSystemRepository implements ManagedSystemRepository {

    private final ManagedSystemMapper mapper;

    MyBatisManagedSystemRepository(ManagedSystemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ManagedSystem> findBySystemKey(String systemKey) {
        return Optional.ofNullable(mapper.selectBySystemKey(systemKey)).map(MyBatisManagedSystemRepository::toDomain);
    }

    private static ManagedSystem toDomain(ManagedSystemRow row) {
        return new ManagedSystem(
                row.id(),
                row.systemKey(),
                row.name(),
                row.description(),
                row.environment(),
                SystemStatus.valueOf(row.status()),
                row.lockVersion());
    }
}
