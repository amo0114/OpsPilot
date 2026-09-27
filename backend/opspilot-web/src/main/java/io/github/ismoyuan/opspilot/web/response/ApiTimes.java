package io.github.ismoyuan.opspilot.web.response;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** API 时间统一为 ISO 8601 UTC、固定毫秒位（05 §5），如 2026-09-25T07:21:31.120Z。 */
public final class ApiTimes {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private ApiTimes() {}

    public static String format(Instant instant) {
        return instant == null ? null : FORMAT.format(instant);
    }
}
