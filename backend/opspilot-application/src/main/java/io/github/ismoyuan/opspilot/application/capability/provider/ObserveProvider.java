package io.github.ismoyuan.opspilot.application.capability.provider;

import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import java.time.Instant;

/**
 * 一个 OBSERVE 能力的 Provider（06 §17～§20、07 §59，08 TASK-052～057）：只按已准入调用中的受信 Binding 与已解析窗口访问外部系统，
 * 返回真实结果或失败；不拼装 response_payload 或 Observation——成功结果由 {@link ProviderCapabilityInvoker} 统一交给
 * ObserveResultPipeline（脱敏在持久化与 AI 之前）。
 *
 * <p>在任何数据库事务之外调用；网络等待不得超过调用方给出的 {@code deadline}（由 {@link ProviderCapabilityInvoker} 按能力超时算出，
 * 也覆盖其后的管线处理）；一次调用对应一次 Provider 请求，不自动重试（06 §34）。预期的外部失败以 {@link ProviderOutcome.Failed} 返回，
 * 不抛出；超时后线程会被中断，应尽快结束。
 */
public interface ObserveProvider {

    CapabilityKey capability();

    ProviderOutcome fetch(AdmittedInvocation invocation, Instant deadline);
}
