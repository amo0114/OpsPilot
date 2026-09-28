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
    INCIDENT_NOT_FOUND(ErrorCategory.NOT_FOUND, "故障不存在。"),
    INCIDENT_STATE_CONFLICT(ErrorCategory.CONFLICT, "当前故障状态不允许执行该操作。"),
    INCIDENT_VERSION_CONFLICT(ErrorCategory.CONFLICT, "故障已被其他操作更新，请刷新后重试。"),
    PENDING_APPROVAL_EXISTS(ErrorCategory.CONFLICT, "存在待审批的处理方案，请先拒绝或撤回审批。"),
    RESOURCE_NOT_IN_SYSTEM(ErrorCategory.RULE_VIOLATION, "指定的组件不属于该业务系统。"),
    DIAGNOSIS_NOT_FOUND(ErrorCategory.NOT_FOUND, "诊断版本不存在。"),
    /** Diagnosis 草稿的主假设、引用证据或支持证据不满足 01 §20 / 04 §34。 */
    DIAGNOSIS_INVARIANT_VIOLATION(ErrorCategory.RULE_VIOLATION, "诊断结论缺少合法的主假设或支持证据。"),
    /**
     * 结果所属 run 已不是当前 run（01 §11、05 §93）：内部审计处置，旧轮结果只保留审计、不产生领域写入；
     * 不是 Incident 状态，也不由公开 API 返回。
     */
    STALE_RUN_RESULT(ErrorCategory.CONFLICT, "该结果属于已结束的调查轮次，未被采用。"),
    /** 同一 Observation × Hypothesis 已有 Evidence：Intent 拒绝码，原关系保持不变（05 §83、§93）。 */
    EVIDENCE_LINK_ALREADY_EXISTS(ErrorCategory.CONFLICT, "该观测与假设之间已存在证据关系，不能重复或改写。"),
    /** AI Runtime 无法连接、拒绝内部认证或返回服务端错误（05 §31、§93）。 */
    AI_RUNTIME_UNAVAILABLE(ErrorCategory.DEPENDENCY_UNAVAILABLE, "AI 服务暂不可用，请稍后重试。"),
    /** 在调用方给定的等待上限内没有得到 AI Runtime 响应（05 §89）。 */
    AI_RUNTIME_TIMEOUT(ErrorCategory.DEPENDENCY_TIMEOUT, "AI 服务响应超时，请稍后重试。"),
    /** AI Runtime 输出不符合 v1 协议（05 §93）：未知字段、联合类型不合法、缺字段或越界；不回显原始输出。 */
    AI_OUTPUT_INVALID(ErrorCategory.DEPENDENCY_INVALID_RESPONSE, "AI 服务返回的内容不符合协议。"),
    /**
     * 运行记录 error_code（05 §93、07 §54～§55）：旧 Java 进程退出时仍为 RUNNING 的 AgentStep 或只读调用，由启动恢复写入；
     * 不计入模型连续失败，不由公开 API 返回。
     */
    PROCESS_INTERRUPTED(ErrorCategory.INTERNAL, "处理进程在该步骤完成前重启，该步骤未完成。"),
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
