package io.github.ismoyuan.opspilot.application.capability.sanitize;

import java.util.Objects;

/**
 * 已经过 {@link Sanitizer} 的外部文本。只能由 Sanitizer 产生，需要“已脱敏”前提的出口（如 RawResultStore，06 §32）只接受本类型，
 * 由类型而不是调用方约定保证原始文本不绕过脱敏。
 */
public final class SanitizedText {

    private final String content;

    SanitizedText(String content) {
        this.content = Objects.requireNonNull(content, "content");
    }

    public String content() {
        return content;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SanitizedText text && content.equals(text.content);
    }

    @Override
    public int hashCode() {
        return content.hashCode();
    }

    @Override
    public String toString() {
        return "SanitizedText[length=" + content.length() + "]";
    }
}
