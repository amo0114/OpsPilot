package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.system;

import io.github.ismoyuan.opspilot.application.system.CapabilityBindingRepository;
import io.github.ismoyuan.opspilot.domain.system.CapabilityBinding;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisCapabilityBindingRepository implements CapabilityBindingRepository {

    private final CapabilityBindingMapper mapper;

    MyBatisCapabilityBindingRepository(CapabilityBindingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<CapabilityBinding> findByResourceIdAndCapabilityKey(long managedResourceId, String capabilityKey) {
        return Optional.ofNullable(mapper.selectByResourceIdAndCapabilityKey(managedResourceId, capabilityKey))
                .map(MyBatisCapabilityBindingRepository::toDomain);
    }

    @Override
    public List<CapabilityBinding> findAllByResourceId(long managedResourceId) {
        return mapper.selectAllByResourceId(managedResourceId).stream()
                .map(MyBatisCapabilityBindingRepository::toDomain)
                .toList();
    }

    private static CapabilityBinding toDomain(CapabilityBindingRow row) {
        return new CapabilityBinding(row.id(), row.managedResourceId(), row.capabilityKey(), row.enabled());
    }
}
