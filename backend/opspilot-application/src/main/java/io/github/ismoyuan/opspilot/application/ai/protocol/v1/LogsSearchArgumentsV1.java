package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import java.util.List;

/**
 * logs.search 参数（06 §49～§50）：窗口、至少一个级别、至多 5 个普通文本关键字（每个 1～64 字符、非空白、无控制字符）；
 * 不接受 LogQL、正则或管道。
 */
public record LogsSearchArgumentsV1(WindowKey windowKey, List<LogSeverity> severity, List<String> keywords)
        implements CapabilityArguments {

    public static final int MAX_KEYWORDS = 5;
    public static final int MAX_KEYWORD_LENGTH = 64;

    public LogsSearchArgumentsV1 {
        ProtocolChecks.required("windowKey", windowKey);
        severity = ProtocolChecks.uniqueList("severity", severity, 1, LogSeverity.values().length);
        keywords = ProtocolChecks.uniqueList("keywords", keywords, 0, MAX_KEYWORDS);
        for (String keyword : keywords) {
            ProtocolChecks.text("keywords", keyword, MAX_KEYWORD_LENGTH);
            if (keyword.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
                throw ProtocolChecks.invalid("keywords");
            }
        }
    }
}
