package io.github.ismoyuan.opspilot.infrastructure.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.CacheInspectArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogsSearchArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 07 §58、08 TASK-047：唯一规范 JSON——键序、Map Key、null、空对象与数组顺序的统一规则。 */
class JacksonCanonicalJsonWriterTest {

    private final JacksonCanonicalJsonWriter writer = new JacksonCanonicalJsonWriter();

    @Test
    void typedArgumentsAreWrittenWithSortedFields() {
        assertThat(writer.write(new MetricsQueryArgumentsV1("http.request.latency.p99", WindowKey.LAST_15_MIN, true)))
                .isEqualTo("{\"comparePreviousWindow\":true,\"metricKey\":\"http.request.latency.p99\","
                        + "\"windowKey\":\"LAST_15_MIN\"}");
        assertThat(writer.write(new CacheInspectArgumentsV1())).isEqualTo("{}");
    }

    /** 数组保持顺序（不做集合语义重排）。 */
    @Test
    void arraysKeepTheirOrder() {
        assertThat(writer.write(new LogsSearchArgumentsV1(
                        WindowKey.LAST_30_MIN,
                        List.of(LogSeverity.WARN, LogSeverity.ERROR),
                        List.of("timeout", "redis"))))
                .isEqualTo("{\"keywords\":[\"timeout\",\"redis\"],\"severity\":[\"WARN\",\"ERROR\"],"
                        + "\"windowKey\":\"LAST_30_MIN\"}");
    }

    @Test
    void mapKeysAreSortedRecursivelyAndNullFieldsAreDropped() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", 1);
        inner.put("a", null);
        inner.put("m", List.of(Map.of("y", 2, "b", 3)));
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("b", inner);
        outer.put("a", "x");

        assertThat(writer.write(outer)).isEqualTo("{\"a\":\"x\",\"b\":{\"m\":[{\"b\":3,\"y\":2}],\"z\":1}}");
    }

    /** 数据库改写键序与空白后的载荷，与同一值直接写出的结果相同。 */
    @Test
    void storedPayloadsCanonicalizeToTheSameText() {
        String written = writer.write(new MetricsQueryArgumentsV1("db.pool.active", WindowKey.INCIDENT_CONTEXT, false));
        String stored = "{\"windowKey\": \"INCIDENT_CONTEXT\", \"metricKey\": \"db.pool.active\", "
                + "\"comparePreviousWindow\": false}";

        assertThat(writer.canonicalize(stored)).isEqualTo(written);
        assertThat(writer.canonicalize("{\"a\": null, \"b\": {}}")).isEqualTo("{\"b\":{}}");
        assertThatThrownBy(() -> writer.canonicalize("{not json")).isInstanceOf(IllegalArgumentException.class);
    }
}
