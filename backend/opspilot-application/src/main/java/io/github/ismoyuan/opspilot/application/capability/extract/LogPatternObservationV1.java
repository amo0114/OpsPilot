package io.github.ismoyuan.opspilot.application.capability.extract;

import io.github.ismoyuan.opspilot.application.ai.protocol.v1.LogSeverity;
import io.github.ismoyuan.opspilot.application.capability.result.TimeRange;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * log-pattern.observation / 1（06 §55、§117）：一个日志模式一条 Observation，Evidence 可精确引用；查询没有取得任何模式时为一条
 * pattern 为空、count 为 0 的记录，如实表示“窗口内没有匹配”。
 *
 * @param totalMatches 与 truncated 描述整次查询
 */
public record LogPatternObservationV1(
        TimeRange window,
        String pattern,
        LogSeverity severity,
        long count,
        Instant firstSeen,
        Instant lastSeen,
        List<String> samples,
        long totalMatches,
        boolean truncated) {

    public static final String SCHEMA_NAME = "log-pattern.observation";
    public static final int SCHEMA_VERSION = 1;

    public LogPatternObservationV1 {
        Objects.requireNonNull(window, "window");
        samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
    }
}
