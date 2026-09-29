package io.github.ismoyuan.opspilot.application.capability.result;

import java.util.List;
import java.util.Objects;

/** 结果与 Observation 载荷记录的共同构造校验；异常文本只含字段名（Codec 会把它作为诊断，07 §96）。 */
public final class ResultChecks {

    private ResultChecks() {}

    public static String text(String field, String value) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public static String optionalText(String field, String value) {
        return value == null ? null : text(field, value);
    }

    public static long nonNegative(String field, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    public static Long optionalNonNegative(String field, Long value) {
        return value == null ? null : nonNegative(field, value);
    }

    /** 有限且非负；NaN、无穷不得进入结果（空时序、NaN 不能当 0，也不能写入 JSON）。 */
    public static double finite(String field, double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(field + " must be finite and not negative");
        }
        return value;
    }

    public static Double optionalFinite(String field, Double value) {
        return value == null ? null : finite(field, value);
    }

    public static Double optionalRatio(String field, Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 1)) {
            throw new IllegalArgumentException(field + " must be within [0, 1]");
        }
        return value;
    }

    public static <T> List<T> list(String field, List<T> values) {
        Objects.requireNonNull(values, field);
        for (T value : values) {
            Objects.requireNonNull(value, field + " element");
        }
        return List.copyOf(values);
    }
}
