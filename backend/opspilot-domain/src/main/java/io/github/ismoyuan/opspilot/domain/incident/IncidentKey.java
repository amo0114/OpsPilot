package io.github.ismoyuan.opspilot.domain.incident;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 用户可见的故障编号 INC-YYYYMMDD-NNNN（05 §6），日期为 UTC 检测日，序号当日递增、至少 4 位。 */
public record IncidentKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("INC-(\\d{8})-(\\d{4,9})");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    public IncidentKey {
        Objects.requireNonNull(value, "value");
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches() || Integer.parseInt(matcher.group(2)) < 1) {
            throw new IllegalArgumentException("invalid incident key");
        }
        LocalDate.parse(matcher.group(1), DAY);
    }

    public static IncidentKey of(LocalDate day, int sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be >= 1");
        }
        return new IncidentKey("INC-" + day.format(DAY) + "-" + String.format("%04d", sequence));
    }

    /** 同一天全部编号共享的前缀，如 INC-20260927-。 */
    public static String dayPrefix(LocalDate day) {
        return "INC-" + day.format(DAY) + "-";
    }

    public int sequence() {
        return Integer.parseInt(value.substring(value.lastIndexOf('-') + 1));
    }

    @Override
    public String toString() {
        return value;
    }
}
