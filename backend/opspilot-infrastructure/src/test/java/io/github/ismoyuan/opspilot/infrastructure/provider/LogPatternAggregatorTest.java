package io.github.ismoyuan.opspilot.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogsSearchArgumentsV1;
import io.github.ismoyuan.opspilot.application.ai.protocol.v1.WindowKey;
import io.github.ismoyuan.opspilot.application.capability.logs.LogLine;
import io.github.ismoyuan.opspilot.application.capability.logs.LogLineNormalizer;
import io.github.ismoyuan.opspilot.application.capability.logs.LogPatternAggregator;
import io.github.ismoyuan.opspilot.application.capability.logs.LogsSettings;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1.LogPattern;
import io.github.ismoyuan.opspilot.application.capability.result.TimeRange;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import io.github.ismoyuan.opspilot.domain.system.binding.LokiResourceBindingV1;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 08 TASK-053、06 §49～§54：确定性归一化与聚合、先脱敏后归一化、数量上限，以及 LogQL 只由受信标签与转义后的普通文本构成。 */
class LogPatternAggregatorTest {

    static final Instant END = Instant.parse("2026-09-29T08:00:00Z");
    static final TimeRange WINDOW = new TimeRange(END.minusSeconds(900), END);

    private final Sanitizer sanitizer = new Sanitizer(new SanitizerSettings(true));

    @Test
    void normalizationReplacesVariablePartsWithPlaceholders() {
        assertThat(LogLineNormalizer.normalize(
                        "2026-09-29T07:59:01.123Z ERROR 1 --- [shortlink] [nio-8080-exec-7] c.n.s.RedirectService :"
                                + " Redis command timed out after 2000 ms"))
                .isEqualTo("<TIME> ERROR <NUM> --- [shortlink] [nio-<NUM>-exec-<NUM>] c.n.s.RedirectService :"
                        + " Redis command timed out after <NUM> ms");
        assertThat(
                        LogLineNormalizer.normalize(
                                "req 3f2b8c1a-9d4e-4f6a-8b7c-1234567890ab from 10.0.3.17:52344 trace 7fa3b2c9d1e04f56 at 07:59:01,5"))
                .isEqualTo("req <ID> from <IP> trace <HEX> at <TIME>");
        // 纯字母单词、带字母前缀的编号（IPv4、HikariPool-1 的前缀）不被误替换
        assertThat(LogLineNormalizer.normalize("IPv4 HikariPool-1 deadbeefcafe"))
                .isEqualTo("IPv4 HikariPool-<NUM> deadbeefcafe");
    }

    @Test
    void severityComesFromTheUppercaseLevelWord() {
        assertThat(LogLineNormalizer.severity("2026-09-29 ERROR 1 --- x")).isEqualTo(LogSeverity.ERROR);
        assertThat(LogLineNormalizer.severity("WARNING slow")).isEqualTo(LogSeverity.WARN);
        assertThat(LogLineNormalizer.severity("{\"level\":\"INFO\",\"msg\":\"ok\"}"))
                .isEqualTo(LogSeverity.INFO);
        assertThat(LogLineNormalizer.severity("an error happened")).isNull();
    }

    /** 同模板的行聚为一组；按次数降序；每组至多 2 条不同样例，样例与模板均已脱敏（JWT 未被数字替换破坏）。 */
    @Test
    void linesAreGroupedByTemplateAndSanitizedBeforeNormalization() {
        List<LogLine> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            lines.add(line(
                    -600 + i * 10, "ERROR Redis command timed out after " + (2000 + i) + " ms password=hunter" + i));
        }
        lines.add(line(-100, "WARN token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.c2lnbmF0dXJl rejected"));
        lines.add(line(-90, "WARN token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI5OTk5In0.b3RoZXJzaWc rejected"));

        LogsSearchResultV1 result = aggregator(LogsSettings.DEFAULTS).aggregate(WINDOW, lines, false);

        assertThat(result.totalMatches()).isEqualTo(7);
        assertThat(result.patterns()).extracting(LogPattern::count).containsExactly(5L, 2L);
        LogPattern redis = result.patterns().getFirst();
        assertThat(redis.pattern()).isEqualTo("ERROR Redis command timed out after <NUM> ms password=[REDACTED]");
        assertThat(redis.severity()).isEqualTo(LogSeverity.ERROR);
        assertThat(redis.firstSeen()).isEqualTo(END.minusSeconds(600));
        assertThat(redis.lastSeen()).isEqualTo(END.minusSeconds(560));
        assertThat(redis.samples())
                .containsExactly(
                        "ERROR Redis command timed out after 2000 ms password=[REDACTED]",
                        "ERROR Redis command timed out after 2001 ms password=[REDACTED]");
        LogPattern token = result.patterns().get(1);
        assertThat(token.pattern()).isEqualTo("WARN token [REDACTED] rejected");
        assertThat(token.samples()).containsExactly("WARN token [REDACTED] rejected"); // 脱敏后相同的样例只留一条
        assertThat(result.toString()).doesNotContain("hunter").doesNotContain("eyJ");
    }

    @Test
    void limitsWindowAndOrderingAreDeterministic() {
        List<LogLine> lines = new ArrayList<>();
        for (int pattern = 0; pattern < 12; pattern++) {
            for (int i = 0; i <= pattern % 3; i++) {
                lines.add(line(-800 + pattern * 20 + i, "ERROR failure kind-" + (char) ('a' + pattern)));
            }
        }
        lines.add(line(-901, "ERROR before the window"));
        lines.add(line(0, "ERROR at the window end"));
        lines.add(line(-5, "   "));
        LogsSettings settings = new LogsSettings(500, 10, 2, 5, 2000);

        LogsSearchResultV1 result = aggregator(settings).aggregate(WINDOW, lines, true);

        assertThat(result.truncated()).isTrue();
        assertThat(result.totalMatches()).isEqualTo(25); // 24 行模式 + 1 个空行；窗口外 2 行不计
        assertThat(result.patterns()).hasSize(10);
        // 次数 3 的在前，同次数按最近出现降序
        assertThat(result.patterns())
                .extracting(LogPattern::pattern)
                .startsWith("ERROR failure kind-l", "ERROR failure kind-i", "ERROR failure kind-f");
        assertThat(aggregator(settings).aggregate(WINDOW, lines, true)).isEqualTo(result);
        assertThat(aggregator(settings).aggregate(WINDOW, lines.reversed(), true))
                .isEqualTo(result);
    }

    @Test
    void overlongLinesAreClippedAfterSanitizing() {
        String line = "ERROR " + "x".repeat(3000) + " password=secret";
        LogsSearchResultV1 result =
                aggregator(new LogsSettings(500, 10, 2, 5, 100)).aggregate(WINDOW, List.of(line(-10, line)), false);

        assertThat(result.patterns().getFirst().pattern()).hasSize(100).doesNotContain("secret");
    }

    /** LogQL：标签来自受信 Binding（排序、转义）；级别为固定正则；关键字按 RE2 转义为字面量并不区分大小写，再按双引号串转义。 */
    @Test
    void theLogQueryIsBuiltOnlyFromTrustedLabelsAndEscapedPlainText() {
        String query = LokiLogsSearchProvider.logQuery(
                new LokiResourceBindingV1(Map.of("app", "shortlink-project", "env", "de\"mo")),
                new LogsSearchArgumentsV1(
                        WindowKey.LAST_15_MIN,
                        List.of(LogSeverity.WARN, LogSeverity.ERROR),
                        List.of("redis", "a.b(c)*", "say \"hi\" \\ now", "`|~ \".*\"")));

        assertThat(query)
                .isEqualTo("{app=\"shortlink-project\",env=\"de\\\"mo\"}"
                        + " |~ \"(?i)redis\""
                        + " |~ \"(?i)a\\\\.b\\\\(c\\\\)\\\\*\""
                        + " |~ \"(?i)say \\\"hi\\\" \\\\\\\\ now\""
                        + " |~ \"(?i)`\\\\|~ \\\"\\\\.\\\\*\\\"\""
                        + " | regexp \"(?s)^.*?\\\\b(?P<opspilot_level>ERROR|WARN(?:ING)?|INFO|DEBUG)\\\\b\""
                        + " | opspilot_level=~\"ERROR|WARN(?:ING)?\"");
    }

    /**
     * B16-R1：Loki 端的级别筛选与 Java 端的级别识别是同一规则——行内第一个大写级别词；正文中后出现的其他级别词不改变该行的级别。
     */
    @Test
    void lokiLevelSelectionAgreesWithTheExtractedSeverity() {
        // RE2 的 (?P<name>) 在 Java 中写作 (?<name>)，且 Java 组名不允许下划线
        Pattern loki = Pattern.compile(LokiLogsSearchProvider.LEVEL_PATTERN.replace(
                "(?P<" + LokiLogsSearchProvider.LEVEL_LABEL + ">", "(?<level>"));
        for (String line : List.of(
                "2026-09-29 10:00:00 INFO Retrying request after ERROR from upstream",
                "ERROR failed; WARN follows",
                "WARNING disk almost full ERROR later",
                "DEBUG trace",
                "no level word here, error in lower case",
                "stack trace line\nERROR on the second line")) {
            Matcher matcher = loki.matcher(line);
            LogSeverity expected = LogLineNormalizer.severity(line);
            if (expected == null) {
                assertThat(matcher.find()).as(line).isFalse();
            } else {
                assertThat(matcher.find()).as(line).isTrue();
                assertThat(LogLineNormalizer.severity(matcher.group("level")))
                        .as(line)
                        .isEqualTo(expected);
            }
        }
        assertThat(LogLineNormalizer.severity("2026-09-29 10:00:00 INFO Retrying request after ERROR from upstream"))
                .isEqualTo(LogSeverity.INFO);
    }

    private LogPatternAggregator aggregator(LogsSettings settings) {
        return new LogPatternAggregator(sanitizer, settings);
    }

    private static LogLine line(int offsetSeconds, String text) {
        return new LogLine(END.plusSeconds(offsetSeconds), text);
    }
}
