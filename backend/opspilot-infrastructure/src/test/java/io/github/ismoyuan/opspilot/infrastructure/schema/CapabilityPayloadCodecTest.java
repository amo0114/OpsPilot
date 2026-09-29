package io.github.ismoyuan.opspilot.infrastructure.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.capability.ObservationDraft;
import io.github.ismoyuan.opspilot.application.capability.extract.CacheStatusObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.DatabaseStatusObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.LogPatternObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.MetricObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.ObservationExtractor;
import io.github.ismoyuan.opspilot.application.capability.extract.QueueStatusObservationV1;
import io.github.ismoyuan.opspilot.application.capability.extract.ServiceStatusObservationV1;
import io.github.ismoyuan.opspilot.application.capability.result.CapabilityResult;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException.Reason;
import io.github.ismoyuan.opspilot.infrastructure.capability.CapabilityResultSamples;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 结果与 Observation 载荷按 Schema 编码（B15）：往返一致、null 显式写出、时间为 ISO-8601 文本；只编码注册类型，解码时构造器仍拒绝不自洽的值。
 */
class CapabilityPayloadCodecTest {

    private final JacksonSchemaCodecRegistry registry = new JacksonSchemaCodecRegistry();

    private static final Map<String, Class<?>> OBSERVATION_TYPES = Map.of(
            MetricObservationV1.SCHEMA_NAME, MetricObservationV1.class,
            LogPatternObservationV1.SCHEMA_NAME, LogPatternObservationV1.class,
            CacheStatusObservationV1.SCHEMA_NAME, CacheStatusObservationV1.class,
            DatabaseStatusObservationV1.SCHEMA_NAME, DatabaseStatusObservationV1.class,
            QueueStatusObservationV1.SCHEMA_NAME, QueueStatusObservationV1.class,
            ServiceStatusObservationV1.SCHEMA_NAME, ServiceStatusObservationV1.class);

    @Test
    void resultsAndTheirObservationsRoundTrip() {
        Sanitizer sanitizer = new Sanitizer(new SanitizerSettings(true));
        ObservationExtractor extractor = new ObservationExtractor(registry);
        List<CapabilityResult> results = List.of(
                CapabilityResultSamples.metrics(),
                CapabilityResultSamples.logs(),
                CapabilityResultSamples.cache(),
                CapabilityResultSamples.slowQueries(),
                CapabilityResultSamples.serverSummary(),
                CapabilityResultSamples.connections(),
                CapabilityResultSamples.lockWaits(),
                CapabilityResultSamples.queue(null),
                CapabilityResultSamples.service());
        for (CapabilityResult result : results) {
            String name = result.resultSchema().name();
            String json = registry.encode(name, 1, result);
            assertThat(registry.decode(name, 1, json, result.getClass()))
                    .as(name)
                    .isEqualTo(result);
            for (ObservationDraft draft :
                    extractor.extract(sanitizer.sanitizeResult(result), CapabilityResultSamples.NOW)) {
                Object payload = registry.decode(
                        draft.schemaName(),
                        draft.schemaVersion(),
                        draft.payload(),
                        OBSERVATION_TYPES.get(draft.schemaName()));
                assertThat(registry.encode(draft.schemaName(), 1, payload)).isEqualTo(draft.payload());
            }
        }
    }

    @Test
    void unknownValuesAreWrittenAsExplicitNullsAndTimesAsIsoText() {
        String json = registry.encode(QueueInspectResultV1.SCHEMA_NAME, 1, CapabilityResultSamples.queue(null));

        assertThat(json)
                .contains("\"lag\":null")
                .contains("\"lastGeneratedAt\":\"2026-09-21T12:00:00Z\"")
                .contains("\"queueType\":\"REDIS_STREAM\"");
    }

    @Test
    void onlyRegisteredTypesAreEncoded() {
        assertThat(catchReason(() -> registry.encode("metrics.query.result", 2, CapabilityResultSamples.metrics())))
                .isEqualTo(Reason.UNKNOWN_SCHEMA);
        assertThat(catchReason(() -> registry.encode("metrics.query.result", 1, CapabilityResultSamples.cache())))
                .isEqualTo(Reason.TYPE_MISMATCH);
        assertThat(catchReason(() -> registry.encode("metrics.query.result", 1, null)))
                .isEqualTo(Reason.TYPE_MISMATCH);
    }

    /** 已存载荷解码仍经构造器：单点趋势等不自洽的值被拒绝。 */
    @Test
    void decodingRejectsFabricatedValues() {
        String json = registry.encode(MetricsQueryResultV1.SCHEMA_NAME, 1, CapabilityResultSamples.metrics())
                .replace("\"sampleCount\":34", "\"sampleCount\":1");

        assertThatThrownBy(() -> registry.decode(MetricsQueryResultV1.SCHEMA_NAME, 1, json, MetricsQueryResultV1.class))
                .isInstanceOf(SchemaPayloadException.class)
                .extracting(ex -> ((SchemaPayloadException) ex).reason())
                .isEqualTo(Reason.INVALID_PAYLOAD);
    }

    private static Reason catchReason(Runnable action) {
        try {
            action.run();
        } catch (SchemaPayloadException ex) {
            return ex.reason();
        }
        throw new AssertionError("expected SchemaPayloadException");
    }
}
