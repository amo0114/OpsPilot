package io.github.ismoyuan.opspilot.application.error;

import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.error.OpsPilotException;
import java.util.Map;

/**
 * Use Case 层错误，也是基础设施异常的翻译目标（07 §105）：Provider/SDK 异常作为 cause 保留，不直接冒到浏览器。
 */
public class ApplicationException extends OpsPilotException {

    public ApplicationException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of(), null);
    }

    public ApplicationException(ErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    public ApplicationException(ErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(errorCode, message, details, cause);
    }
}
