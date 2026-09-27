package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * v1 协议字段校验，与 contracts/ai-runtime/v1 的 JSON Schema 逐条对应。失败抛 IllegalArgumentException，
 * 信息只含字段名，不回显取值（05 §93）。
 */
final class ProtocolChecks {

    static final int PROTOCOL_VERSION = 1;

    private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Pattern INCIDENT_KEY = Pattern.compile("INC-[0-9]{8}-[0-9]{4,}");
    private static final Pattern RESOURCE_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern METRIC_KEY = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)*");
    private static final Pattern EVENT_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private ProtocolChecks() {}

    static void protocolVersion(int value) {
        if (value != PROTOCOL_VERSION) {
            throw invalid("protocolVersion");
        }
    }

    /** 数据库 BIGINT 主键：≥ 1。 */
    static void id(String field, long value) {
        if (value < 1) {
            throw invalid(field);
        }
    }

    /** runNo、版本号：INT UNSIGNED 范围内且 ≥ 1。 */
    static void positive(String field, int value) {
        if (value < 1) {
            throw invalid(field);
        }
    }

    /** 可为 0 的计数；为空视为缺失。 */
    static void count(String field, Integer value) {
        if (value == null || value < 0) {
            throw invalid(field);
        }
    }

    static <T> T required(String field, T value) {
        if (value == null) {
            throw invalid(field);
        }
        return value;
    }

    /**
     * 至少一个非空白码点、按 Unicode 码点计数不超过 max（与 JSON Schema maxLength 一致）。空白以 {@link #isWhiteSpace}
     * 的显式集合判断，不用 String.isBlank（它把 U+001C～001F 当空白而不认 U+00A0，与 Schema/Pydantic 不同）。
     */
    static String text(String field, String value, int max) {
        if (value == null
                || value.codePoints().allMatch(ProtocolChecks::isWhiteSpace)
                || value.codePointCount(0, value.length()) > max) {
            throw invalid(field);
        }
        return value;
    }

    /** Unicode White_Space，与 contracts/ai-runtime/v1 Schema 的非空白字符类逐项一致。 */
    static boolean isWhiteSpace(int codePoint) {
        return (codePoint >= 0x09 && codePoint <= 0x0D)
                || codePoint == 0x20
                || codePoint == 0x85
                || codePoint == 0xA0
                || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0x202F
                || codePoint == 0x205F
                || codePoint == 0x3000;
    }

    /** 可缺省；给出时同 {@link #text}。 */
    static String optionalText(String field, String value, int max) {
        return value == null ? null : text(field, value, max);
    }

    static String correlationId(String value) {
        return matches("correlationId", value, CORRELATION_ID);
    }

    static String incidentKey(String value) {
        return matches("incidentKey", value, INCIDENT_KEY);
    }

    static String resourceKey(String value) {
        return matches("resourceKey", value, RESOURCE_KEY);
    }

    static String metricKey(String value) {
        String key = matches("metricKey", value, METRIC_KEY);
        if (key.length() > 128) {
            throw invalid("metricKey");
        }
        return key;
    }

    static String eventType(String value) {
        return matches("eventType", value, EVENT_TYPE);
    }

    /** 固定常量字段（capabilityKey、key、受控上限等）。 */
    static void constant(String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw invalid(field);
        }
    }

    /** 非空列表，元素非空；copy 后不可变。 */
    static <T> List<T> list(String field, List<T> values) {
        required(field, values);
        for (T value : values) {
            required(field, value);
        }
        return List.copyOf(values);
    }

    /** 元素不重复（JSON Schema uniqueItems），数量在 [min, max]。 */
    static <T> List<T> uniqueList(String field, List<T> values, int min, int max) {
        List<T> copy = list(field, values);
        if (copy.size() < min || copy.size() > max || new HashSet<>(copy).size() != copy.size()) {
            throw invalid(field);
        }
        return copy;
    }

    static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("invalid protocol field: " + field);
    }

    private static String matches(String field, String value, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }
}
