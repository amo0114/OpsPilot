package io.github.ismoyuan.opspilot.application.capability.sanitize;

import io.github.ismoyuan.opspilot.application.capability.result.CacheInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.CapabilityResult;
import io.github.ismoyuan.opspilot.application.capability.result.DatabaseInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.MetricsQueryResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.QueueInspectResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.ServiceInspectResultV1;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * 外部数据的基础脱敏（08 TASK-049、06 §31～§32、§54、02 §33、CAP-INV-007）：Provider 返回的文本在进入 response_payload、
 * raw_result_ref 文件、Observation、调用错误文案与 AI 之前经过这里。
 *
 * <p>强制清理（不可关闭）：Authorization / Proxy-Authorization / Cookie / Set-Cookie 头的值，Bearer Token，键名含
 * password、passwd、pwd、secret、token（含 access_token、refresh_token）、api_key、credential、private_key、authorization、
 * cookie 的键值（key=value、key: value、JSON "key": value；键与值的引号可带任意层转义，也可为 URL 编码形式），URL（含 JDBC、
 * redis://）中的密码，JWT，PEM 私钥块，{@code IDENTIFIED BY '...'}。邮箱按 {@link SanitizerSettings#redactEmails()}。
 *
 * <p>规则按固定顺序逐条替换，结果幂等（已脱敏文本再次经过不变）；宁可多遮盖，不漏出凭据。只做模式替换，不保证识别任意自由格式的
 * Secret，Provider 仍须只读取白名单字段（06 §60、§87、§97）。
 */
@Component
public class Sanitizer {

    public static final String REDACTED = "[REDACTED]";

    private static final String SENSITIVE_KEY = "[A-Za-z0-9_.-]*"
            + "(?:passw(?:or)?d|pwd|secret|token|api[_-]?key|credential|private[_-]?key|authorization|cookie)"
            + "[A-Za-z0-9_.-]*";

    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|\\z)", Pattern.DOTALL);

    /** 头部形式的值到行尾（值里可能含空格与分号，如 Cookie: a=1; b=2）。 */
    private static final Pattern HEADER = Pattern.compile(
            "(?i)\\b((?:proxy-)?authorization|set-cookie|cookie)(\\s*[:=][ \\t]*)(?![\"'])([^\\r\\n]+)");

    /** Token 前可以是空白或 URL 编码的空格（%20、+）。 */
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer)(?:\\s+|%20|\\+)[A-Za-z0-9._~+/=-]+");

    /** scheme://user:password@host，user 可为空（redis://:password@host）。 */
    private static final Pattern URL_PASSWORD =
            Pattern.compile("(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]*://[^\\s:/?#@\\[\\]]*):([^\\s/?#@]+)@");

    /**
     * 敏感键及其分隔符：键可带引号（含任意层转义的引号，如嵌在 JSON 字符串里的 {@code \"password\"}，以及 URL 编码的 %22），只从词首或
     * %XX 之后开始匹配（避免长串逐位回溯）；分隔符为 = 或 :（含 URL 编码的 %3D、%3A）。值的范围由 {@link #valueAt} 线性扫描确定，
     * 不用逐字符的正则分支（Java 正则对分支重复逐字符递归，长值会栈溢出，B15-R2）。
     */
    private static final Pattern KEY_PREFIX =
            Pattern.compile("(?i)(?:(?<=%[0-9a-f]{2})|(?<![A-Za-z0-9_.-]))(\\\\*[\"']|%22|)("
                    + SENSITIVE_KEY
                    + ")\\1(\\s*(?:[:=]|%3[ad])\\s*)");

    /** 值在单引号内（可带转义前缀），由 {@link #quotedEnd} 找闭合。 */
    private static final Pattern IDENTIFIED_BY = Pattern.compile("(?i)\\b(identified\\s+by\\s+)(\\\\*)'");

    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");

    private static final Pattern EMAIL =
            Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)++");

    private static final List<UnaryOperator<String>> CREDENTIAL_RULES = List.of(
            replacing(PRIVATE_KEY_BLOCK, match -> REDACTED),
            replacing(HEADER, match -> match.group(1) + match.group(2) + REDACTED),
            replacing(BEARER, match -> match.group(1) + " " + REDACTED),
            replacing(URL_PASSWORD, match -> match.group(1) + ":" + REDACTED + "@"),
            Sanitizer::redactKeyValues,
            Sanitizer::redactIdentifiedBy,
            replacing(JWT, match -> REDACTED));

    private final List<UnaryOperator<String>> rules;

    public Sanitizer(SanitizerSettings settings) {
        this.rules = settings.redactEmails()
                ? Stream.concat(CREDENTIAL_RULES.stream(), Stream.of(replacing(EMAIL, match -> REDACTED)))
                        .toList()
                : CREDENTIAL_RULES;
    }

    /**
     * @param text 外部文本；null 原样返回，便于可空字段
     * @return 已脱敏文本
     */
    public String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (UnaryOperator<String> rule : rules) {
            result = rule.apply(result);
        }
        return result;
    }

    /** 脱敏并标记为可写入需要“已脱敏”前提的出口（06 §32）。 */
    public SanitizedText sanitizeRaw(String text) {
        return new SanitizedText(sanitize(text == null ? "" : text));
    }

    /**
     * 清理结构化结果中来自外部系统的自由文本字段：日志模式与样例、规范化 SQL、连接状态与命令、被锁对象、消费组名、镜像名。
     * 数值、枚举、时间与来自受信 Binding 的 metricKey/unit 不含自由文本，原样保留。
     */
    public SanitizedResult sanitizeResult(CapabilityResult result) {
        CapabilityResult sanitized = switch (result) {
            case MetricsQueryResultV1 metrics -> metrics;
            case CacheInspectResultV1 cache -> cache;
            case LogsSearchResultV1 logs -> sanitizeLogs(logs);
            case DatabaseInspectResultV1 database -> sanitizeDatabase(database);
            case QueueInspectResultV1 queue -> sanitizeQueue(queue);
            case ServiceInspectResultV1 service -> sanitizeService(service);
        };
        return new SanitizedResult(sanitized);
    }

    private LogsSearchResultV1 sanitizeLogs(LogsSearchResultV1 logs) {
        return new LogsSearchResultV1(
                logs.window(),
                logs.totalMatches(),
                logs.truncated(),
                logs.patterns().stream()
                        .map(pattern -> new LogsSearchResultV1.LogPattern(
                                sanitize(pattern.pattern()),
                                pattern.severity(),
                                pattern.count(),
                                pattern.firstSeen(),
                                pattern.lastSeen(),
                                pattern.samples().stream().map(this::sanitize).toList()))
                        .toList());
    }

    private DatabaseInspectResultV1 sanitizeDatabase(DatabaseInspectResultV1 database) {
        DatabaseInspectResultV1.ConnectionSummary connections = database.connectionSummary();
        DatabaseInspectResultV1.LockWaits locks = database.lockWaits();
        return new DatabaseInspectResultV1(
                database.inspectionType(),
                database.serverSummary(),
                connections == null
                        ? null
                        : new DatabaseInspectResultV1.ConnectionSummary(
                                connections.totalConnections(),
                                connections.runningConnections(),
                                connections.states().stream()
                                        .map(state -> new DatabaseInspectResultV1.StateCount(
                                                sanitize(state.state()), state.count()))
                                        .toList(),
                                connections.longest() == null
                                        ? null
                                        : new DatabaseInspectResultV1.LongestConnection(
                                                connections.longest().timeSeconds(),
                                                sanitize(connections.longest().command()),
                                                sanitize(connections.longest().state()))),
                database.slowQueries() == null
                        ? null
                        : database.slowQueries().stream()
                                .map(query -> new DatabaseInspectResultV1.SlowQuery(
                                        sanitize(query.digest()),
                                        sanitize(query.normalizedSql()),
                                        query.executionCount(),
                                        query.averageLatencyMs(),
                                        query.maxLatencyMs(),
                                        query.averageRowsExamined(),
                                        query.lastSeen()))
                                .toList(),
                locks == null
                        ? null
                        : new DatabaseInspectResultV1.LockWaits(
                                locks.waitingCount(),
                                locks.longestWaitSeconds(),
                                locks.waits().stream()
                                        .map(wait -> new DatabaseInspectResultV1.LockWait(
                                                wait.waitingThreadId(),
                                                wait.blockingThreadId(),
                                                wait.waitSeconds(),
                                                sanitize(wait.lockedObject())))
                                        .toList()));
    }

    private QueueInspectResultV1 sanitizeQueue(QueueInspectResultV1 queue) {
        return new QueueInspectResultV1(
                queue.queueType(),
                queue.streamLength(),
                queue.lastGeneratedId(),
                queue.lastGeneratedAt(),
                queue.consumerGroups().stream()
                        .map(group -> new QueueInspectResultV1.ConsumerGroup(
                                sanitize(group.group()),
                                group.consumerCount(),
                                group.pendingCount(),
                                group.lag(),
                                group.lastDeliveredId(),
                                group.lastDeliveredAt()))
                        .toList());
    }

    private ServiceInspectResultV1 sanitizeService(ServiceInspectResultV1 service) {
        return new ServiceInspectResultV1(
                service.runtimeState(),
                service.healthStatus(),
                service.startedAt(),
                service.restartCount(),
                sanitize(service.image()),
                service.exitCode(),
                service.finishedAt());
    }

    private static UnaryOperator<String> replacing(Pattern pattern, Function<MatchResult, String> replacement) {
        return text -> pattern.matcher(text).replaceAll(match -> Matcher.quoteReplacement(replacement.apply(match)));
    }

    /** 已确定的值：结束位置（不含）与保留原有引号形态（含转义层级与 %22）的替换文本。 */
    private record Value(int end, String replacement) {}

    private static String redactKeyValues(String text) {
        Matcher key = KEY_PREFIX.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        int from = 0;
        while (from < text.length() && key.find(from)) {
            Value value = valueAt(text, key.end());
            if (value == null) {
                from = key.end();
                continue;
            }
            out.append(text, copied, key.end()).append(value.replacement());
            copied = value.end();
            from = value.end();
        }
        return copied == 0 ? text : out.append(text, copied, text.length()).toString();
    }

    /**
     * 依次判断：已脱敏标记；%22 包围（到空白或 &amp; 之前找到闭合 %22）；带引号（前缀为 n 个反斜杠加引号，见 {@link #quotedEnd}；
     * 没有闭合时遮盖到行尾）；到分隔符为止的裸值。没有值时为空。
     */
    private static Value valueAt(String text, int start) {
        if (text.startsWith(REDACTED, start)) {
            return new Value(start + REDACTED.length(), REDACTED);
        }
        if (text.regionMatches(start, "%22", 0, 3)) {
            for (int i = start + 3;
                    i < text.length() && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '&';
                    i++) {
                if (text.regionMatches(i, "%22", 0, 3)) {
                    return new Value(i + 3, "%22" + REDACTED + "%22");
                }
            }
        }
        int quote = start;
        while (quote < text.length() && text.charAt(quote) == '\\') {
            quote++;
        }
        if (quote < text.length() && (text.charAt(quote) == '"' || text.charAt(quote) == '\'')) {
            String prefix = text.substring(start, quote + 1);
            int close = quotedEnd(text, quote + 1, quote - start, text.charAt(quote));
            return new Value(close < 0 ? lineEnd(text, quote + 1) : close + 1, prefix + REDACTED + prefix);
        }
        int end = start;
        while (end < text.length() && !isBareValueStop(text.charAt(end))) {
            end++;
        }
        return end == start ? null : new Value(end, REDACTED);
    }

    /**
     * 在同一行内找与开头同一转义层级的闭合引号：恰有 {@code escapes} 个反斜杠紧接其前（更深一层转义的引号不算）；未转义时相邻的两个引号
     * 是值内容（SQL/CSV 的 {@code ''}）。线性扫描，找不到返回 -1。
     */
    private static int quotedEnd(String text, int from, int escapes, char quote) {
        int backslashes = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                return -1;
            }
            if (c == quote && backslashes == escapes) {
                if (escapes == 0 && i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i++;
                    backslashes = 0;
                    continue;
                }
                return i;
            }
            backslashes = c == '\\' ? backslashes + 1 : 0;
        }
        return -1;
    }

    private static int lineEnd(String text, int from) {
        int i = from;
        while (i < text.length() && text.charAt(i) != '\r' && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static boolean isBareValueStop(char c) {
        return Character.isWhitespace(c) || ",;&}])\"'<>".indexOf(c) >= 0;
    }

    private static String redactIdentifiedBy(String text) {
        Matcher prefix = IDENTIFIED_BY.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        while (copied <= text.length() && prefix.find(copied)) {
            String quote = text.substring(prefix.start(2), prefix.end());
            int close = quotedEnd(text, prefix.end(), prefix.group(2).length(), '\'');
            int end = close < 0 ? lineEnd(text, prefix.end()) : close + 1;
            out.append(text, copied, prefix.start(2))
                    .append(quote)
                    .append(REDACTED)
                    .append(quote);
            copied = end;
        }
        return copied == 0 ? text : out.append(text, copied, text.length()).toString();
    }
}
