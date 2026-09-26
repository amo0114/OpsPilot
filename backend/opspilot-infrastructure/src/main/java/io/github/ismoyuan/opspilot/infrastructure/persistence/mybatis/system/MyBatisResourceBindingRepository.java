package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import io.github.ismoyuan.opspilot.application.system.ResourceBindingRepository;
import io.github.ismoyuan.opspilot.domain.system.ResourceBinding;
import io.github.ismoyuan.opspilot.domain.system.SelectorSchema;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisResourceBindingRepository implements ResourceBindingRepository {

    private final ResourceBindingMapper mapper;

    MyBatisResourceBindingRepository(ResourceBindingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ResourceBinding> findAllByResourceId(long managedResourceId) {
        return mapper.selectAllByResourceId(managedResourceId).stream()
                .map(MyBatisResourceBindingRepository::toDomain)
                .toList();
    }

    private static ResourceBinding toDomain(ResourceBindingRow row) {
        return new ResourceBinding(
                row.id(),
                row.managedResourceId(),
                row.dataSourceConnectionId(),
                new SelectorSchema(row.selectorSchemaName(), row.selectorSchemaVersion()),
                row.selectorPayload());
    }
}
