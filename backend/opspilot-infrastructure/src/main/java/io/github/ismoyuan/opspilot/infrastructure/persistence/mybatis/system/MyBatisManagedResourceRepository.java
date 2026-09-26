package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import io.github.ismoyuan.opspilot.application.system.ManagedResourceRepository;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import io.github.ismoyuan.opspilot.domain.system.ResourceStatus;
import io.github.ismoyuan.opspilot.domain.system.ResourceType;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisManagedResourceRepository implements ManagedResourceRepository {

    private final ManagedResourceMapper mapper;

    MyBatisManagedResourceRepository(ManagedResourceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ManagedResource> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisManagedResourceRepository::toDomain);
    }

    @Override
    public Optional<ManagedResource> findBySystemIdAndResourceKey(long managedSystemId, String resourceKey) {
        return Optional.ofNullable(mapper.selectBySystemIdAndResourceKey(managedSystemId, resourceKey))
                .map(MyBatisManagedResourceRepository::toDomain);
    }

    @Override
    public List<ManagedResource> findAllBySystemId(long managedSystemId) {
        return mapper.selectAllBySystemId(managedSystemId).stream()
                .map(MyBatisManagedResourceRepository::toDomain)
                .toList();
    }

    private static ManagedResource toDomain(ManagedResourceRow row) {
        return new ManagedResource(
                row.id(),
                row.managedSystemId(),
                row.resourceKey(),
                row.name(),
                ResourceType.valueOf(row.resourceType()),
                row.description(),
                ResourceStatus.valueOf(row.status()),
                row.lockVersion());
    }
}
