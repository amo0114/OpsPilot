package io.github.ismoyuan.opspilot.application.capability.logs;

/**
 * logs.search 的数量上限（06 §53，均可配置）。
 *
 * @param rawMatchLimit Provider 原始匹配上限，默认 500；达到上限的结果标为截断，计数为下限
 * @param maxPatterns 进入结果的模式数，默认 10
 * @param samplesPerPattern 每个模式的脱敏样例数，默认 2
 * @param aiContextPatterns 每次调用进入 AI Context 的模式数，默认 5（06 §122）
 * @param maxLineLength 单行参与归一化与作为样例的最大字符数，默认 2000，超出截断
 */
public record LogsSettings(
        int rawMatchLimit, int maxPatterns, int samplesPerPattern, int aiContextPatterns, int maxLineLength) {

    public static final LogsSettings DEFAULTS = new LogsSettings(500, 10, 2, 5, 2000);

    public LogsSettings {
        if (rawMatchLimit < 1
                || maxPatterns < 1
                || samplesPerPattern < 0
                || aiContextPatterns < 1
                || aiContextPatterns > maxPatterns
                || maxLineLength < 16) {
            throw new IllegalArgumentException("logs settings are out of range");
        }
    }
}
