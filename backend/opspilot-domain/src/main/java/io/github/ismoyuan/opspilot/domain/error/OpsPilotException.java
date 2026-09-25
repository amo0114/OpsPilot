package io.github.ismoyuan.opspilot.domain.error;

import java.util.Map;

/** 携带 ErrorCode 与结构化上下文的业务异常基类（07 §103～§104）。 */
public abstract class OpsPilotException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    protected OpsPilotException(ErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = Map.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 可安全返回给调用方的上下文；不得放入 Secret 或原始载荷。 */
    public Map<String, Object> details() {
        return details;
    }
}
