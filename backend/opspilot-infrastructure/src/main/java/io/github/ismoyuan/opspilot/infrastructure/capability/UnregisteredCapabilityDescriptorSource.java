package io.github.ismoyuan.opspilot.infrastructure.capability;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityDescriptor;
import io.github.ismoyuan.opspilot.application.investigation.context.CapabilityDescriptorSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Capability Registry 建立前的占位（TASK-044/045 以受信配置构造的真实 Descriptor 替换并删除本类）：没有经过 Registry 校验的能力
 * 一律不进入可执行空间（06 §22），因此如实返回空列表，而不是从绑定表猜测。
 */
@Component
class UnregisteredCapabilityDescriptorSource implements CapabilityDescriptorSource {

    @Override
    public List<CapabilityDescriptor> describe(long incidentId) {
        return List.of();
    }
}
