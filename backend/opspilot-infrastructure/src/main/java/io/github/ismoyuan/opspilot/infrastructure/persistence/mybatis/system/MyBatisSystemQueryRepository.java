package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import io.github.ismoyuan.opspilot.application.system.query.ResourceCapabilityProjection;
import io.github.ismoyuan.opspilot.application.system.query.SystemDetailView;
import io.github.ismoyuan.opspilot.application.system.query.SystemQueryRepository;
import io.github.ismoyuan.opspilot.application.system.query.SystemSummaryView;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisSystemQueryRepository implements SystemQueryRepository {

    private final SystemQueryMapper mapper;

    MyBatisSystemQueryRepository(SystemQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long countSystems() {
        return mapper.countSystems();
    }

    @Override
    public List<SystemSummaryView> findSystems(int offset, int limit) {
        return mapper.selectSystems(offset, limit);
    }

    @Override
    public Optional<SystemDetailView> findSystemDetail(String systemKey) {
        return Optional.ofNullable(mapper.selectSystemByKey(systemKey))
                .map(row -> new SystemDetailView(
                        row.systemKey(),
                        row.name(),
                        row.description(),
                        row.environment(),
                        row.status(),
                        mapper.selectResourcesBySystemId(row.id())));
    }

    @Override
    public boolean systemExists(String systemKey) {
        return mapper.selectSystemByKey(systemKey) != null;
    }

    @Override
    public Optional<ResourceCapabilityProjection> findResource(String systemKey, String resourceKey) {
        return Optional.ofNullable(mapper.selectResourceByKeys(systemKey, resourceKey))
                .map(row -> new ResourceCapabilityProjection(
                        row.toView(),
                        mapper.selectEnabledCapabilityKeys(row.id()),
                        mapper.selectActiveRecoveryPolicies(row.id())));
    }
}
