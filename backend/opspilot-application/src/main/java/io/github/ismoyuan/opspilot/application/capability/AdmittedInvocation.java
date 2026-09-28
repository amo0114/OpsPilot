package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityArguments;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityDefinition;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import java.time.Duration;
import java.time.Instant;

/**
 * 已提交准入的一次调查 OBSERVE 调用：执行它所需的全部受信信息，Provider 只按此执行（06 §17～§20）。
 *
 * @param window 带 windowKey 的能力（metrics.query、logs.search）在准入时解析出的窗口（06 §42），其他能力为空
 */
public record AdmittedInvocation(
        long invocationId,
        long incidentId,
        long investigationId,
        int runNo,
        ManagedResource resource,
        CapabilityDefinition definition,
        ProviderBinding provider,
        CapabilityArguments arguments,
        ResolvedWindow window,
        Instant startedAt) {

    /** 该能力的配置超时（06 §125）；执行方不得超过。 */
    public Duration timeout() {
        return definition.timeout();
    }
}
