package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.InvocationOutcome;
import io.github.ismoyuan.opspilot.application.capability.ObserveResultPipeline;
import io.github.ismoyuan.opspilot.application.capability.QueryWindow;
import io.github.ismoyuan.opspilot.application.capability.ResolvedWindow;
import io.github.ismoyuan.opspilot.application.capability.extract.ObservationExtractor;
import io.github.ismoyuan.opspilot.application.capability.provider.ObserveProvider;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderCapabilityInvoker;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.raw.RawResultStore;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizedText;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.ProviderType;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusMetricBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.infrastructure.capability.CapabilityResultSamples;
import io.github.ismoyuan.opspilot.infrastructure.schema.SchemaCodecs;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * 生产 Invoker：每个能力至多一个 Provider（06 §18）；B16-R1 总期限——能力超时同时覆盖 Provider 取数与其后的整段管线（含原始结果写入），
 * 到期返回 TIMEOUT 且不采用迟到的成功；期限内正常完成照常成功。
 */
class ProviderCapabilityInvokerTest {

    private volatile Duration rawStoreDelay = Duration.ZERO;

    private final RawResultStore slowStore = new RawResultStore() {
        @Override
        public String store(long incidentId, long invocationId, SanitizedText content) {
            sleep(rawStoreDelay);
            return "file:///tmp/raw/invocation-" + invocationId + ".json.gz";
        }

        @Override
        public String read(String ref) {
            throw new UnsupportedOperationException();
        }
    };

    private final ObserveResultPipeline pipeline = new ObserveResultPipeline(
            new Sanitizer(new SanitizerSettings(true)),
            slowStore,
            SchemaCodecs.registry(),
            new ObservationExtractor(SchemaCodecs.registry()));

    @Test
    void twoProvidersForOneCapabilityAreRejectedAtStartup() {
        ObserveProvider provider = provider((invocation, deadline) -> fetched());
        assertThatThrownBy(
                        () -> new ProviderCapabilityInvoker(List.of(provider, provider), pipeline, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metrics.query");
    }

    @Test
    void aCompleteCallWithinTheTimeoutSucceeds() {
        InvocationOutcome outcome =
                invoker(provider((invocation, deadline) -> fetched())).invoke(invocation(200));

        assertThat(outcome).isInstanceOf(InvocationOutcome.Succeeded.class);
        assertThat(((InvocationOutcome.Succeeded) outcome).observations()).hasSize(1);
    }

    /** B16-R1 复现：Provider 立即返回，但原始结果写入慢于 20 ms 超时——返回 TIMEOUT，且不等写入完成。 */
    @Test
    void slowPostProcessingAfterTheProviderStillHonoursTheTimeout() {
        rawStoreDelay = Duration.ofMillis(300);

        long started = System.nanoTime();
        InvocationOutcome outcome =
                invoker(provider((invocation, deadline) -> fetched())).invoke(invocation(20));

        assertThat(outcome)
                .isEqualTo(new InvocationOutcome.Failed(
                        ErrorCode.TIMEOUT, "Capability invocation exceeded the capability timeout"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));
    }

    /** Provider 忽略了给它的期限：Invoker 仍在期限处返回 TIMEOUT，并中断该取数。 */
    @Test
    void aProviderThatOverrunsItsDeadlineIsCutOff() {
        long started = System.nanoTime();
        InvocationOutcome outcome = invoker(provider((invocation, deadline) -> {
                    sleep(Duration.ofSeconds(2));
                    return fetched();
                }))
                .invoke(invocation(50));

        assertThat(outcome).isInstanceOf(InvocationOutcome.Failed.class);
        assertThat(((InvocationOutcome.Failed) outcome).errorCode()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
    }

    /** Provider 收到的期限就是能力超时算出的同一期限。 */
    @Test
    void theProviderReceivesTheInvocationDeadline() {
        Instant before = Instant.now();
        Instant[] seen = new Instant[1];
        invoker(provider((invocation, deadline) -> {
                    seen[0] = deadline;
                    return new ProviderOutcome.Failed(ErrorCode.PROVIDER_UNAVAILABLE, "down");
                }))
                .invoke(invocation(1_000));

        assertThat(seen[0]).isBetween(before.plusMillis(1_000), Instant.now().plusMillis(1_000));
    }

    /**
     * 恢复采样的冻结期限（04 §80、B28-R1）：早于能力超时时，Provider 收到的就是该期限；忽略期限的 Provider 在该期限处被截断为
     * TIMEOUT，期限之后取得的数据不会成为结果。
     */
    @Test
    void aFrozenDeadlineEarlierThanTheTimeoutBoundsTheCall() {
        Instant frozen = Instant.now().plusMillis(200);
        AdmittedInvocation bounded = withDeadline(invocation(5_000), frozen);
        Instant[] seen = new Instant[1];
        long started = System.nanoTime();

        InvocationOutcome outcome = invoker(provider((invocation, deadline) -> {
                    seen[0] = deadline;
                    sleep(Duration.ofSeconds(2));
                    return fetched();
                }))
                .invoke(bounded);

        assertThat(seen[0]).isEqualTo(frozen);
        assertThat(outcome).isInstanceOf(InvocationOutcome.Failed.class);
        assertThat(((InvocationOutcome.Failed) outcome).errorCode()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_000));
        assertThat(withDeadline(invocation(1_000), Instant.now().plusSeconds(60))
                        .deadline(Instant.EPOCH))
                .as("较晚的冻结期限不放宽能力超时")
                .isEqualTo(Instant.EPOCH.plusMillis(1_000));
    }

    @Test
    void unexpectedProviderExceptionsPropagateToTheExecutionService() {
        ProviderCapabilityInvoker invoker = invoker(provider((invocation, deadline) -> {
            throw new IllegalStateException("provider bug");
        }));
        assertThatThrownBy(() -> invoker.invoke(invocation(1_000))).isInstanceOf(IllegalStateException.class);
    }

    private ProviderCapabilityInvoker invoker(ObserveProvider provider) {
        return new ProviderCapabilityInvoker(List.of(provider), pipeline, Clock.systemUTC());
    }

    private static ObserveProvider provider(BiFunction<AdmittedInvocation, Instant, ProviderOutcome> fetch) {
        return new ObserveProvider() {
            @Override
            public CapabilityKey capability() {
                return CapabilityKey.METRICS_QUERY;
            }

            @Override
            public ProviderOutcome fetch(AdmittedInvocation invocation, Instant deadline) {
                return fetch.apply(invocation, deadline);
            }
        };
    }

    private static ProviderOutcome fetched() {
        return new ProviderOutcome.Fetched(CapabilityResultSamples.metrics(), "metric raw\n", Instant.now());
    }

    private static AdmittedInvocation invocation(long timeoutMillis) {
        Instant now = Instant.now();
        return ProviderInvocations.admitted(
                CapabilityKey.METRICS_QUERY,
                ProviderType.PROMETHEUS,
                "http://prometheus:9090",
                null,
                new PrometheusResourceBindingV1(
                        Map.of("application", "shortlink-project"),
                        Map.of("http.request.latency.p99", new PrometheusMetricBindingV1("up", "ms"))),
                new MetricsQueryArgumentsV1("http.request.latency.p99", WindowKey.LAST_15_MIN, false),
                new ResolvedWindow(new QueryWindow(now.minusSeconds(900), now), null),
                Duration.ofMillis(timeoutMillis));
    }

    private static AdmittedInvocation withDeadline(AdmittedInvocation invocation, Instant deadlineAt) {
        return new AdmittedInvocation(
                invocation.invocationId(),
                invocation.incidentId(),
                null,
                null,
                invocation.resource(),
                invocation.definition(),
                invocation.provider(),
                invocation.arguments(),
                invocation.window(),
                invocation.startedAt(),
                deadlineAt);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
