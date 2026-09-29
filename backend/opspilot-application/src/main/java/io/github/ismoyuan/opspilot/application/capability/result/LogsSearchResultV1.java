package io.github.ismoyuan.opspilot.application.capability.result;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * logs.search.result / 1（06 §51～§53）：Provider 在准入窗口内取得的匹配日志经确定性归一化后的 Pattern Summary，不含全部原始行。
 * Pattern 与 Sample 的数量上限是 Provider 配置（TASK-053），此处只保证自洽：各 Pattern 计数之和不超过 totalMatches；每个模式的
 * 首末时间在半开的查询窗口 [start, end) 内（B15-R2），只出现一次时首末相同，样例不多于出现次数。
 *
 * @param totalMatches Provider 实际取得的匹配行数
 * @param truncated 是否因原始匹配上限而未取全；为真时计数只是下限
 */
public record LogsSearchResultV1(TimeRange window, long totalMatches, boolean truncated, List<LogPattern> patterns)
        implements CapabilityResult {

    public static final String SCHEMA_NAME = "logs.search.result";
    public static final int SCHEMA_VERSION = 1;

    /**
     * @param pattern 归一化后的模板（UUID、数字、IP、时间等已替换为占位符）
     * @param severity 日志级别无法识别时为空
     * @param samples 少量代表性原始行
     */
    public record LogPattern(
            String pattern,
            LogSeverity severity,
            long count,
            Instant firstSeen,
            Instant lastSeen,
            List<String> samples) {

        public LogPattern {
            ResultChecks.text("pattern", pattern);
            if (count < 1) {
                throw new IllegalArgumentException("count must be positive");
            }
            Objects.requireNonNull(firstSeen, "firstSeen");
            Objects.requireNonNull(lastSeen, "lastSeen");
            if (firstSeen.isAfter(lastSeen)) {
                throw new IllegalArgumentException("firstSeen must not be after lastSeen");
            }
            samples = ResultChecks.list("samples", samples);
            if (count == 1 && !firstSeen.equals(lastSeen)) {
                throw new IllegalArgumentException("a single occurrence must have firstSeen equal to lastSeen");
            }
            if (samples.size() > count) {
                throw new IllegalArgumentException("samples must not outnumber count");
            }
        }
    }

    public LogsSearchResultV1 {
        Objects.requireNonNull(window, "window");
        ResultChecks.nonNegative("totalMatches", totalMatches);
        patterns = ResultChecks.list("patterns", patterns);
        long counted = 0;
        for (LogPattern pattern : patterns) {
            counted += pattern.count();
            if (!window.contains(pattern.firstSeen()) || !window.contains(pattern.lastSeen())) {
                throw new IllegalArgumentException("pattern times must lie within window");
            }
        }
        if (counted > totalMatches) {
            throw new IllegalArgumentException("pattern counts must not exceed totalMatches");
        }
    }

    @Override
    public CapabilitySchema resultSchema() {
        return new CapabilitySchema(SCHEMA_NAME, SCHEMA_VERSION);
    }
}
