package io.github.ismoyuan.opspilot.application.capability.logs;

import java.time.Instant;
import java.util.Objects;

/** Provider 取得的一条原始日志（尚未脱敏）。 */
public record LogLine(Instant timestamp, String line) {

    public LogLine {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(line, "line");
    }
}
