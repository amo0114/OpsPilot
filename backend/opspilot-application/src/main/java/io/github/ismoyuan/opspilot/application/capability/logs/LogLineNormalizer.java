package io.github.ismoyuan.opspilot.application.capability.logs;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志行的确定性归一化（06 §52，不用 LLM 聚类）：时间 → &lt;TIME&gt;，UUID → &lt;ID&gt;，IP → &lt;IP&gt;，长十六进制串 → &lt;HEX&gt;，
 * 数字 → &lt;NUM&gt;，按固定顺序替换，相同模板的行因此聚为一组。输入应已脱敏（先脱敏再归一化，避免把 Token 中的数字替换后
 * 让脱敏规则认不出来）。级别只从行内大写级别词识别（Spring/Logback 默认格式），识别不到为空。
 */
public final class LogLineNormalizer {

    private record Rule(Pattern pattern, String placeholder) {}

    private static final List<Rule> RULES = List.of(
            new Rule(
                    Pattern.compile(
                            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?"),
                    "<TIME>"),
            new Rule(Pattern.compile("(?<![0-9:])\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?![0-9])"), "<TIME>"),
            new Rule(
                    Pattern.compile(
                            "(?<![0-9A-Fa-f-])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![0-9A-Fa-f-])"),
                    "<ID>"),
            new Rule(Pattern.compile("(?<![0-9.])\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?![0-9.])"), "<IP>"),
            new Rule(
                    Pattern.compile(
                            "(?<![0-9A-Za-z])(?:0x)?(?=[0-9A-Fa-f]*[0-9])(?=[0-9A-Fa-f]*[A-Fa-f])[0-9A-Fa-f]{8,}(?![0-9A-Za-z])"),
                    "<HEX>"),
            new Rule(Pattern.compile("(?<![A-Za-z_])\\d+(?:\\.\\d+)?"), "<NUM>"));

    private static final Pattern LEVEL = Pattern.compile("\\b(ERROR|WARN(?:ING)?|INFO|DEBUG)\\b");

    private LogLineNormalizer() {}

    public static String normalize(String line) {
        String result = line;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.placeholder());
        }
        return result;
    }

    /** @return 行内第一个大写级别词；没有时为空 */
    public static LogSeverity severity(String line) {
        Matcher matcher = LEVEL.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        return switch (matcher.group(1)) {
            case "ERROR" -> LogSeverity.ERROR;
            case "WARN", "WARNING" -> LogSeverity.WARN;
            case "INFO" -> LogSeverity.INFO;
            default -> LogSeverity.DEBUG;
        };
    }
}
