package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.MetricsQueryArgumentsV1;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.QueryWindow;
import io.github.ismoyuan.opspilot.application.capability.ResolvedWindow;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricPoint;
import io.github.ismoyuan.opspilot.application.capability.metrics.MetricSeriesSummarizer;
import io.github.ismoyuan.opspilot.application.capability.provider.ObserveProvider;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusMetricBindingV1;
import io.github.ismoyuan.opspilot.domain.system.binding.PrometheusResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.connection.HttpConnectionConfigV1;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * metrics.query 的 Prometheus Provider（08 TASK-052、06 §36～§46）。AI 只给 metricKey/windowKey/comparePreviousWindow；PromQL 只来自资源
 * Binding 中该 MetricKey 的受信模板（完整表达式，含标签筛选与单位换算，不做任何占位替换，AI 文本从不进入查询，06 §23、§38～§40）。
 *
 * <p>对准入时解析的当前窗口（及比较时的等长紧邻前一窗口）各做一次 query_range（06 §42），步长为 max(最小步长, 窗口/最大点数)；
 * 模板必须得到至多一条序列，多条视为绑定配置错误。NaN/Inf 点保留在原始结果中、统计时剔除（{@link MetricSeriesSummarizer}），不当 0；
 * 负值不符合 V0.1 指标语义，按响应非法处理。原始结果按“窗口 时刻 值”逐行存放。认证按连接配置与 credentialRef（{@link ProviderAuthentication}）；
 * 使用调用方给定的总期限。
 */
final class PrometheusMetricsQueryProvider implements ObserveProvider {

    static final String QUERY_RANGE_PATH = "/api/v1/query_range";

    private final ProviderHttpClient http;
    private final ProviderAuthentication authentication;
    private final MetricSeriesSummarizer summarizer;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;
    private final Duration minStep;
    private final int maxPoints;

    PrometheusMetricsQueryProvider(
            ProviderHttpClient http,
            ProviderAuthentication authentication,
            MetricSeriesSummarizer summarizer,
            Clock clock,
            Duration minStep,
            int maxPoints) {
        this.http = http;
        this.authentication = authentication;
        this.summarizer = summarizer;
        this.clock = clock;
        this.minStep = minStep;
        this.maxPoints = maxPoints;
    }

    @Override
    public CapabilityKey capability() {
        return CapabilityKey.METRICS_QUERY;
    }

    @Override
    public ProviderOutcome fetch(AdmittedInvocation invocation, Instant deadline) {
        try {
            if (!(invocation.provider().selector() instanceof PrometheusResourceBindingV1 selector)) {
                throw new ProviderCallException(
                        ErrorCode.INVALID_BINDING, "Resource binding is not a Prometheus binding");
            }
            MetricsQueryArgumentsV1 arguments = (MetricsQueryArgumentsV1) invocation.arguments();
            PrometheusMetricBindingV1 metric = selector.metrics().get(arguments.metricKey());
            if (metric == null) {
                throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Metric is no longer bound to the resource");
            }
            ResolvedWindow window = invocation.window();
            URI endpoint = ProviderHttpClient.resolve(
                    ProviderHttpClient.baseUri(
                            invocation.provider().connection().endpoint()),
                    QUERY_RANGE_PATH);
            String authorization = authentication.authorization(
                    invocation.provider().connection(), HttpConnectionConfigV1.PROMETHEUS_SCHEMA_NAME);
            List<MetricPoint> current =
                    queryRange(endpoint, authorization, metric.queryTemplate(), window.current(), deadline);
            List<MetricPoint> previous = window.previous() == null
                    ? List.of()
                    : queryRange(endpoint, authorization, metric.queryTemplate(), window.previous(), deadline);
            Instant observedAt = clock.instant();
            return new ProviderOutcome.Fetched(
                    summarizer.summarize(arguments.metricKey(), metric.unit(), window, current, previous),
                    rawLines(arguments.metricKey(), current, previous),
                    observedAt);
        } catch (ProviderCallException ex) {
            return ex.outcome();
        }
    }

    private List<MetricPoint> queryRange(
            URI endpoint, String authorization, String query, QueryWindow window, Instant deadline) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("query", query);
        parameters.put("start", seconds(window.start()));
        parameters.put("end", seconds(window.end()));
        parameters.put("step", seconds(step(window)));
        byte[] body = http.query(endpoint, parameters, authorization, deadline);
        return parseMatrix(body);
    }

    /** 步长不小于最小步长，且使一个窗口内的点数不超过上限。 */
    Duration step(QueryWindow window) {
        long millis = window.length().toMillis();
        long perPoint = (millis + maxPoints - 1) / maxPoints;
        return Duration.ofMillis(Math.max(minStep.toMillis(), perPoint));
    }

    private List<MetricPoint> parseMatrix(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException ex) {
            throw invalid();
        }
        JsonNode data = root.path("data");
        if (!"success".equals(root.path("status").asString(""))
                || !"matrix".equals(data.path("resultType").asString(""))
                || !data.path("result").isArray()) {
            throw invalid();
        }
        JsonNode result = data.path("result");
        if (result.size() > 1) {
            throw new ProviderCallException(
                    ErrorCode.INVALID_BINDING, "Metric template must return at most one series");
        }
        List<MetricPoint> points = new ArrayList<>();
        if (result.isEmpty()) {
            return points;
        }
        JsonNode values = result.get(0).path("values");
        if (!values.isArray()) {
            throw invalid();
        }
        for (JsonNode pair : values) {
            if (!pair.isArray()
                    || pair.size() != 2
                    || !pair.get(0).isNumber()
                    || !pair.get(1).isString()) {
                throw invalid();
            }
            double value = parseValue(pair.get(1).asString());
            if (Double.isFinite(value) && value < 0) {
                throw new ProviderCallException(
                        ErrorCode.PROVIDER_RESPONSE_INVALID, "Metric returned a negative value");
            }
            long millis = pair.get(0).decimalValue().movePointRight(3).longValue();
            points.add(new MetricPoint(Instant.ofEpochMilli(millis), value));
        }
        return points;
    }

    /** Prometheus 以字符串给出样本值，NaN 与 ±Inf 为合法取值。 */
    private static double parseValue(String text) {
        return switch (text) {
            case "NaN" -> Double.NaN;
            case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    yield Double.parseDouble(text);
                } catch (NumberFormatException ex) {
                    throw invalid();
                }
            }
        };
    }

    private static String rawLines(String metricKey, List<MetricPoint> current, List<MetricPoint> previous) {
        StringBuilder raw = new StringBuilder("metric ").append(metricKey).append('\n');
        current.forEach(point -> raw.append("current ")
                .append(point.timestamp())
                .append(' ')
                .append(point.value())
                .append('\n'));
        previous.forEach(point -> raw.append("previous ")
                .append(point.timestamp())
                .append(' ')
                .append(point.value())
                .append('\n'));
        return raw.toString();
    }

    private static String seconds(Instant instant) {
        return BigDecimal.valueOf(instant.toEpochMilli(), 3).toPlainString();
    }

    private static String seconds(Duration duration) {
        return BigDecimal.valueOf(duration.toMillis(), 3).toPlainString();
    }

    private static ProviderCallException invalid() {
        return new ProviderCallException(
                ErrorCode.PROVIDER_RESPONSE_INVALID, "Prometheus response is not a valid range query result");
    }
}
