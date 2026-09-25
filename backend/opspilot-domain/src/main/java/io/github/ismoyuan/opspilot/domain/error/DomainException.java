package io.github.ismoyuan.opspilot.domain.error;

import java.util.Map;

/** 领域规则被违反。 */
public class DomainException extends OpsPilotException {

    public DomainException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public DomainException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(errorCode, message, details, null);
    }
}
