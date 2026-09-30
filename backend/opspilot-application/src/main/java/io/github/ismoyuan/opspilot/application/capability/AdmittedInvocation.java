package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CapabilityArguments;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityDefinition;
import io.github.ismoyuan.opspilot.domain.system.ManagedResource;
import java.time.Duration;
import java.time.Instant;

/**
 * 已提交准入的一次 OBSERVE 调用（调查调用或恢复采样调用）：执行它所需的全部受信信息，Provider 只按此执行（06 §17～§20）。
 *
 * @param investigationId 恢复采样调用为空
 * @param runNo 恢复采样调用为空
 * @param window 带 windowKey 的能力（metrics.query、logs.search）在准入时解析出的窗口（06 §42），其他能力为空
 * @param deadlineAt 调用不得越过的绝对期限：恢复采样为 Verification 创建时冻结的 deadline_at（04 §80），调查调用为空（只受能力超时约束）
 */
public record AdmittedInvocation(
        long invocationId,
        long incidentId,
        Long investigationId,
        Integer runNo,
        ManagedResource resource,
        CapabilityDefinition definition,
        ProviderBinding provider,
        CapabilityArguments arguments,
        ResolvedWindow window,
        Instant startedAt,
        Instant deadlineAt) {

    /** 该能力的配置超时（06 §125）；执行方不得超过。 */
    public Duration timeout() {
        return definition.timeout();
    }

    /** 从 {@code now} 开始调用时的期限：能力超时与 {@link #deadlineAt} 中较早者。 */
    public Instant deadline(Instant now) {
        Instant byTimeout = now.plus(timeout());
        return deadlineAt != null && deadlineAt.isBefore(byTimeout) ? deadlineAt : byTimeout;
    }
}
