package io.github.ismoyuan.opspilot.domain.error;

/**
 * 对外错误码，名称与 05 §93 冻结目录一致；前端只按 code 分支。
 *
 * <p>具体业务码随其所属 Task 加入，不在此预先铺满。
 */
public enum ErrorCode {
    REQUEST_VALIDATION_FAILED(ErrorCategory.INVALID_REQUEST, "请求参数不合法。"),
    RESOURCE_NOT_FOUND(ErrorCategory.NOT_FOUND, "请求的资源不存在。"),
    SYSTEM_NOT_FOUND(ErrorCategory.NOT_FOUND, "业务系统不存在。"),
    /** credentialRef 无法解析为可用凭据（08 TASK-009）；属部署配置错误，不在 05 §93 公开目录。 */
    SECRET_NOT_FOUND(ErrorCategory.INTERNAL, "所需凭据未配置，请检查部署环境。"),
    /** 未预期的程序错误；不属于 05 §93 业务目录，仅作兜底。 */
    INTERNAL_ERROR(ErrorCategory.INTERNAL, "服务内部错误，请凭 requestId 排查。");

    private final ErrorCategory category;
    private final String defaultMessage;

    ErrorCode(ErrorCategory category, String defaultMessage) {
        this.category = category;
        this.defaultMessage = defaultMessage;
    }

    public ErrorCategory category() {
        return category;
    }

    /** 面向用户的固定文案；异常的内部 message 只进日志，不回显给调用方。 */
    public String defaultMessage() {
        return defaultMessage;
    }
}
