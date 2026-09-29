package io.github.ismoyuan.opspilot.application.capability.logs;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1;
import io.github.ismoyuan.opspilot.application.capability.result.LogsSearchResultV1.LogPattern;
import io.github.ismoyuan.opspilot.application.capability.result.TimeRange;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把原始匹配日志确定性地聚合为 logs.search.result / 1（06 §51～§53）：只取窗口 [start, end) 内的行，按时间（再按内容）排序；每行先脱敏、
 * 再截断到行长上限、再归一化，相同模板为一组；组按出现次数降序、最近出现降序、模板字典序排列，取前 maxPatterns 个；每组保留最早出现的
 * 至多 samplesPerPattern 条不同的脱敏样例。空行计入匹配总数但不形成模式。不做趋势或异常判定。
 */
public class LogPatternAggregator {

    private final Sanitizer sanitizer;
    private final LogsSettings settings;

    public LogPatternAggregator(Sanitizer sanitizer, LogsSettings settings) {
        this.sanitizer = sanitizer;
        this.settings = settings;
    }

    private static final class Group {
        final String pattern;
        final LogSeverity severity;
        long count;
        Instant firstSeen;
        Instant lastSeen;
        final List<String> samples = new ArrayList<>();

        Group(String pattern, LogSeverity severity) {
            this.pattern = pattern;
            this.severity = severity;
        }
    }

    /**
     * @param truncated Provider 是否因原始匹配上限而未取全
     */
    public LogsSearchResultV1 aggregate(TimeRange window, List<LogLine> lines, boolean truncated) {
        List<LogLine> inWindow = lines.stream()
                .filter(line -> window.contains(line.timestamp()))
                .sorted(Comparator.comparing(LogLine::timestamp).thenComparing(LogLine::line))
                .toList();
        Map<String, Group> groups = new LinkedHashMap<>();
        for (LogLine line : inWindow) {
            String sanitized = clip(sanitizer.sanitize(line.line()));
            if (sanitized.isEmpty()) {
                continue; // 空行只计入匹配总数，不形成模式
            }
            String pattern = LogLineNormalizer.normalize(sanitized);
            Group group = groups.computeIfAbsent(pattern, key -> new Group(key, LogLineNormalizer.severity(sanitized)));
            group.count++;
            if (group.firstSeen == null) {
                group.firstSeen = line.timestamp();
            }
            group.lastSeen = line.timestamp();
            if (group.samples.size() < settings.samplesPerPattern() && !group.samples.contains(sanitized)) {
                group.samples.add(sanitized);
            }
        }
        List<LogPattern> patterns = groups.values().stream()
                .sorted(Comparator.comparingLong((Group group) -> group.count)
                        .reversed()
                        .thenComparing((Group group) -> group.lastSeen, Comparator.reverseOrder())
                        .thenComparing(group -> group.pattern))
                .limit(settings.maxPatterns())
                .map(group -> new LogPattern(
                        group.pattern, group.severity, group.count, group.firstSeen, group.lastSeen, group.samples))
                .toList();
        return new LogsSearchResultV1(window, inWindow.size(), truncated, patterns);
    }

    private String clip(String line) {
        String trimmed = line.strip();
        if (trimmed.codePointCount(0, trimmed.length()) <= settings.maxLineLength()) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, settings.maxLineLength()));
    }
}
