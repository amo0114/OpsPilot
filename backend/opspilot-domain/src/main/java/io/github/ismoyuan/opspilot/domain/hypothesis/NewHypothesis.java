package io.github.ismoyuan.opspilot.domain.hypothesis;

import io.github.ismoyuan.opspilot.domain.error.DomainException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.Map;

/**
 * 待创建的待验证原因（03 §28、05 §81），初始状态必为 PENDING。标题与描述创建后不改写（03 §38）；
 * 上限与 V003 列长度一致（按字符计），超限以 REQUEST_VALIDATION_FAILED 拒绝而不是留给数据库。
 *
 * @param description 可为空；空白视为未提供
 */
public record NewHypothesis(long investigationId, String title, String description) {

    public static final int TITLE_MAX = 200;
    public static final int DESCRIPTION_MAX = 2000;

    public NewHypothesis {
        title = optionalText("title", title, TITLE_MAX);
        if (title == null) {
            throw invalid("title", "BLANK");
        }
        description = optionalText("description", description, DESCRIPTION_MAX);
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
                "Invalid hypothesis field " + field + ": " + reason,
                Map.of("field", field, "reason", reason));
    }
}
