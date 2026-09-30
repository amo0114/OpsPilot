package io.github.ismoyuan.opspilot.application.capability.provider;

import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.CapabilityInvoker;
import io.github.ismoyuan.opspilot.application.capability.InvocationOutcome;
import io.github.ismoyuan.opspilot.application.capability.ObserveResultPipeline;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * CapabilityInvoker 的生产实现：按能力选唯一的 {@link ObserveProvider} 取数，成功结果一律经 {@link ObserveResultPipeline} 组装
 * （Provider 无法绕过脱敏与确定性提取），失败原样记为调用失败。每个能力至多一个 Provider，重复注册启动即失败。
 *
 * <p>总期限（B16-R1）：从开始调用起算能力超时（{@link AdmittedInvocation#timeout()}），恢复采样另不得越过 Verification 冻结的
 * deadline（{@link AdmittedInvocation#deadline}，B28-R1），同一期限交给 Provider 约束网络等待，并覆盖其后的
 * 解析、聚合与整段管线（含原始结果写入）。取数与管线在独立虚拟线程中执行，调用方最多等到期限：到期即中断该线程并返回
 * TIMEOUT，迟到的结果不再被采用（此时可能已写出无引用的脱敏原始结果文件）。
 *
 * <p>尚未实现 Provider 的能力（TASK-054～057 之前）返回 CAPABILITY_INVOCATION_FAILED；调查循环在 TASK-058 才接入执行服务。
 */
@Service
public class ProviderCapabilityInvoker implements CapabilityInvoker {

    static final String NO_PROVIDER = "No provider is available for this capability";
    static final String TIMED_OUT = "Capability invocation exceeded the capability timeout";

    private final Map<CapabilityKey, ObserveProvider> providers = new EnumMap<>(CapabilityKey.class);
    private final ObserveResultPipeline pipeline;
    private final Clock clock;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("capability-invocation-", 0).factory());

    public ProviderCapabilityInvoker(List<ObserveProvider> providers, ObserveResultPipeline pipeline, Clock clock) {
        for (ObserveProvider provider : providers) {
            if (this.providers.putIfAbsent(provider.capability(), provider) != null) {
                throw new IllegalStateException(
                        "more than one provider for " + provider.capability().key());
            }
        }
        this.pipeline = pipeline;
        this.clock = clock;
    }

    @Override
    public InvocationOutcome invoke(AdmittedInvocation invocation) {
        ObserveProvider provider = providers.get(invocation.definition().key());
        if (provider == null) {
            return new InvocationOutcome.Failed(ErrorCode.CAPABILITY_INVOCATION_FAILED, NO_PROVIDER);
        }
        Instant deadline = invocation.deadline(clock.instant());
        Future<InvocationOutcome> task = executor.submit(() -> complete(provider, invocation, deadline));
        try {
            long remaining =
                    Math.max(0, Duration.between(clock.instant(), deadline).toNanos());
            return task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            return new InvocationOutcome.Failed(ErrorCode.TIMEOUT, TIMED_OUT);
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return new InvocationOutcome.Failed(ErrorCode.TIMEOUT, TIMED_OUT);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (ex.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("capability invocation failed", ex.getCause());
        }
    }

    private InvocationOutcome complete(ObserveProvider provider, AdmittedInvocation invocation, Instant deadline) {
        return switch (provider.fetch(invocation, deadline)) {
            case ProviderOutcome.Fetched fetched ->
                pipeline.succeeded(
                        invocation.definition(),
                        invocation.incidentId(),
                        invocation.invocationId(),
                        fetched.result(),
                        fetched.rawResult(),
                        fetched.observedAt());
            case ProviderOutcome.Failed failed ->
                new InvocationOutcome.Failed(failed.errorCode(), failed.safeMessage());
        };
    }
}
