package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogsSearchArgumentsV1;
import io.github.ismoyuan.opspilot.application.capability.AdmittedInvocation;
import io.github.ismoyuan.opspilot.application.capability.QueryWindow;
import io.github.ismoyuan.opspilot.application.capability.logs.LogLine;
import io.github.ismoyuan.opspilot.application.capability.logs.LogLineNormalizer;
import io.github.ismoyuan.opspilot.application.capability.logs.LogPatternAggregator;
import io.github.ismoyuan.opspilot.application.capability.provider.ObserveProvider;
import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.application.capability.result.TimeRange;
import io.github.ismoyuan.opspilot.domain.capability.CapabilityKey;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.binding.LokiResourceBindingV1;
import io.github.ismoyuan.opspilot.domain.system.connection.HttpConnectionConfigV1;
import java.math.BigInteger;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * logs.search 的 Loki Provider（08 TASK-053、06 §47～§57）。AI 只给 windowKey/severity/keywords；LogQL 由 Java 构造（06 §24、§49）：
 * 流选择器来自资源 Binding 的受信标签；每个关键字作为普通文本不区分大小写地匹配——先按 RE2 转义全部元字符（AI 文本从不作正则），多个
 * 关键字须同时出现；级别按行内第一个大写级别词解析后筛选；所有字符串再按 LogQL 双引号串转义。认证按连接配置与 credentialRef。
 *
 * <p>对准入时解析的窗口做一次 query_range（newest-first，至多 rawMatchLimit 行），取满上限即标为截断；聚合、脱敏与归一化由
 * {@link LogPatternAggregator} 完成。原始结果逐行“时刻 TAB 日志”，日志内换行转义为 \n，保证一条日志一行。
 */
final class LokiLogsSearchProvider implements ObserveProvider {

    static final String QUERY_RANGE_PATH = "/loki/api/v1/query_range";

    private final ProviderHttpClient http;
    private final ProviderAuthentication authentication;
    private final LogPatternAggregator aggregator;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;
    private final int rawMatchLimit;

    LokiLogsSearchProvider(
            ProviderHttpClient http,
            ProviderAuthentication authentication,
            LogPatternAggregator aggregator,
            Clock clock,
            int rawMatchLimit) {
        this.http = http;
        this.authentication = authentication;
        this.aggregator = aggregator;
        this.clock = clock;
        this.rawMatchLimit = rawMatchLimit;
    }

    @Override
    public CapabilityKey capability() {
        return CapabilityKey.LOGS_SEARCH;
    }

    @Override
    public ProviderOutcome fetch(AdmittedInvocation invocation, Instant deadline) {
        try {
            if (!(invocation.provider().selector() instanceof LokiResourceBindingV1 selector)) {
                throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Resource binding is not a Loki binding");
            }
            LogsSearchArgumentsV1 arguments = (LogsSearchArgumentsV1) invocation.arguments();
            QueryWindow window = invocation.window().current();
            URI endpoint = ProviderHttpClient.resolve(
                    ProviderHttpClient.baseUri(
                            invocation.provider().connection().endpoint()),
                    QUERY_RANGE_PATH);
            String authorization = authentication.authorization(
                    invocation.provider().connection(), HttpConnectionConfigV1.LOKI_SCHEMA_NAME);
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("query", logQuery(selector, arguments));
            parameters.put("start", nanos(window.start()));
            parameters.put("end", nanos(window.end()));
            parameters.put("limit", Integer.toString(rawMatchLimit));
            parameters.put("direction", "backward");
            List<LogLine> lines = parseStreams(http.query(endpoint, parameters, authorization, deadline));
            Instant observedAt = clock.instant();
            return new ProviderOutcome.Fetched(
                    aggregator.aggregate(
                            new TimeRange(window.start(), window.end()), lines, lines.size() >= rawMatchLimit),
                    rawLines(lines),
                    observedAt);
        } catch (ProviderCallException ex) {
            return ex.outcome();
        }
    }

    /** 解析出的级别标签名；取不常见的名称，避免与流标签冲突。 */
    static final String LEVEL_LABEL = "opspilot_level";

    /** RE2：行内第一个大写级别词（leftmost，(?s) 可跨行）；与 LogLineNormalizer 的级别规则相同。 */
    static final String LEVEL_PATTERN = "(?s)^.*?\\b(?P<" + LEVEL_LABEL + ">ERROR|WARN(?:ING)?|INFO|DEBUG)\\b";

    /**
     * 由受信标签、转义后的普通文本关键字与固定的级别规则构成的 LogQL；不接受任何来自 AI 的查询片段。级别取行内第一个大写级别词（与
     * {@link LogLineNormalizer#severity} 同一规则，(?s) 使其可跨行），在 Loki 内先按它筛选再计入原始上限：正文中偶然出现的其他级别词
     * （如 “INFO Retrying after ERROR”）不会让行被当作 ERROR 取回（B16-R1）。
     */
    static String logQuery(LokiResourceBindingV1 selector, LogsSearchArgumentsV1 arguments) {
        StringJoiner streams = new StringJoiner(",", "{", "}");
        new TreeMap<>(selector.labels()).forEach((name, value) -> streams.add(name + "=" + quoted(value)));
        StringBuilder query = new StringBuilder(streams.toString());
        for (String keyword : arguments.keywords()) {
            query.append(" |~ ").append(quoted("(?i)" + quoteMeta(keyword)));
        }
        query.append(" | regexp ").append(quoted(LEVEL_PATTERN));
        StringJoiner levels = new StringJoiner("|");
        arguments.severity().stream().sorted().forEach(severity -> levels.add(levelWord(severity)));
        query.append(" | ").append(LEVEL_LABEL).append("=~").append(quoted(levels.toString()));
        return query.toString();
    }

    private static String levelWord(LogSeverity severity) {
        return switch (severity) {
            case ERROR -> "ERROR";
            case WARN -> "WARN(?:ING)?";
            case INFO -> "INFO";
            case DEBUG -> "DEBUG";
        };
    }

    /** RE2 字面量：转义全部正则元字符。 */
    static String quoteMeta(String text) {
        StringBuilder escaped = new StringBuilder(text.length() * 2);
        text.codePoints().forEach(codePoint -> {
            if ("\\.+*?()|[]{}^$".indexOf(codePoint) >= 0) {
                escaped.append('\\');
            }
            escaped.appendCodePoint(codePoint);
        });
        return escaped.toString();
    }

    /** LogQL 双引号字符串：转义反斜杠与双引号（关键字已排除控制字符，标签值来自受信配置）。 */
    static String quoted(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private List<LogLine> parseStreams(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException ex) {
            throw invalid();
        }
        JsonNode data = root.path("data");
        if (!"success".equals(root.path("status").asString(""))
                || !"streams".equals(data.path("resultType").asString(""))
                || !data.path("result").isArray()) {
            throw invalid();
        }
        List<LogLine> lines = new ArrayList<>();
        for (JsonNode stream : data.path("result")) {
            JsonNode values = stream.path("values");
            if (!values.isArray()) {
                throw invalid();
            }
            for (JsonNode entry : values) {
                if (!entry.isArray()
                        || entry.size() < 2
                        || !entry.get(0).isString()
                        || !entry.get(1).isString()) {
                    throw invalid();
                }
                lines.add(new LogLine(
                        instant(entry.get(0).asString()), entry.get(1).asString()));
            }
        }
        return lines;
    }

    private static Instant instant(String nanos) {
        try {
            BigInteger[] parts = new BigInteger(nanos).divideAndRemainder(BigInteger.valueOf(1_000_000_000L));
            return Instant.ofEpochSecond(parts[0].longValueExact(), parts[1].longValueExact());
        } catch (NumberFormatException | ArithmeticException ex) {
            throw invalid();
        }
    }

    private static String nanos(Instant instant) {
        return BigInteger.valueOf(instant.getEpochSecond())
                .multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(instant.getNano()))
                .toString();
    }

    private static String rawLines(List<LogLine> lines) {
        StringBuilder raw = new StringBuilder();
        lines.stream()
                .sorted(Comparator.comparing(LogLine::timestamp).thenComparing(LogLine::line))
                .forEach(line -> raw.append(line.timestamp())
                        .append('\t')
                        .append(line.line().replace("\r", "\\r").replace("\n", "\\n"))
                        .append('\n'));
        return raw.toString();
    }

    private static ProviderCallException invalid() {
        return new ProviderCallException(
                ErrorCode.PROVIDER_RESPONSE_INVALID, "Loki response is not a valid log query result");
    }
}
