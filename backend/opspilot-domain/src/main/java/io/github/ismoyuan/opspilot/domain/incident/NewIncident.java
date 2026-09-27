package io.github.ismoyuan.opspilot.domain.incident;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 待创建的故障（05 §20～§21），状态必为 CREATED，编号在持久化时分配。
 * 文本上限与 V002 列长度一致（按字符计），超限在此以 REQUEST_VALIDATION_FAILED 拒绝而不是留给数据库。
 *
 * @param description 可为空；空白视为未提供
 */
public record NewIncident(
        long managedSystemId,
        String title,
        String description,
        String impactSummary,
        IncidentSource createdSource,
        String createdBy,
        Instant startedAt,
        Instant detectedAt) {

    public static final int TITLE_MAX = 200;
    public static final int DESCRIPTION_MAX = 2000;
    public static final int IMPACT_SUMMARY_MAX = 1000;
    public static final int ACTOR_MAX = 128;

    /** “startedAt 不明显晚于当前时间”的容差（05 §21）。 */
    public static final Duration STARTED_AT_TOLERANCE = Duration.ofMinutes(5);

    public NewIncident {
        Objects.requireNonNull(createdSource, "createdSource");
        Objects.requireNonNull(detectedAt, "detectedAt");
        title = requireText("title", title, TITLE_MAX);
        impactSummary = requireText("impactSummary", impactSummary, IMPACT_SUMMARY_MAX);
        createdBy = requireText("createdBy", createdBy, ACTOR_MAX);
        description = optionalText("description", description, DESCRIPTION_MAX);
        // 未提供开始时间时暂用检测时间（05 §20）
        startedAt = startedAt == null ? detectedAt : startedAt;
        if (startedAt.isAfter(detectedAt.plus(STARTED_AT_TOLERANCE))) {
            throw invalid("startedAt", "AFTER_DETECTED_AT");
        }
    }

    private static String requireText(String field, String value, int max) {
        String text = optionalText(field, value, max);
        if (text == null) {
            throw invalid(field, "BLANK");
        }
        return text;
    }

    private static String optionalText(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.codePointCount(0, text.length()) > max) {
            throw invalid(field, "TOO_LONG");
        }
        return text;
    }

    /** details 只含字段名与原因，不回显输入值。 */
    private static DomainException invalid(String field, String reason) {
        return new DomainException(
                ErrorCode.REQUEST_VALIDATION_FAILED,
                "Invalid incident field " + field + ": " + reason,
                Map.of("field", field, "reason", reason));
    }
}
