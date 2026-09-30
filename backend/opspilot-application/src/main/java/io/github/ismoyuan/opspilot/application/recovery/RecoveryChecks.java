package io.github.ismoyuan.opspilot.application.recovery;

import java.util.regex.Pattern;

/** 恢复策略载荷的构造校验；异常文本只含字段名，Codec 把它作为诊断（07 §96），不回显取值。 */
final class RecoveryChecks {

    /** 与 capability_invocation.criterion_key 的库内约束一致（V003），长度不超过列宽 128。 */
    private static final Pattern CRITERION_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");

    /** 与 managed_resource.resource_key 一致（V001）。 */
    private static final Pattern RESOURCE_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private RecoveryChecks() {}

    static <T> T required(String field, T value) {
        if (value == null) {
            throw invalid(field);
        }
        return value;
    }

    static int positive(String field, int value) {
        if (value < 1) {
            throw invalid(field);
        }
        return value;
    }

    /** 至少一个非空白字符，按码点计数不超过 max。 */
    static String text(String field, String value, int max) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max) {
            throw invalid(field);
        }
        return value;
    }

    static String criterionKey(String value) {
        return matches("criterionKey", value, CRITERION_KEY);
    }

    static String resourceKey(String value) {
        return matches("targetResourceKey", value, RESOURCE_KEY);
    }

    /** 有限值；NaN、无穷不能作为阈值。 */
    static Double finite(String field, Double value) {
        if (value == null || !Double.isFinite(value)) {
            throw invalid(field);
        }
        return value;
    }

    static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("invalid recovery policy field: " + field);
    }

    private static String matches(String field, String value, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }
}
