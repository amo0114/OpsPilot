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
    /** 请求的 Capability 不在 Registry 中（05 §93、06 §16 ①）。 */
    CAPABILITY_NOT_FOUND(ErrorCategory.NOT_FOUND, "该能力不存在。"),
    /** 目标资源没有启用该 Capability 的绑定（05 §93、06 §15、§16 ⑤）。 */
    CAPABILITY_NOT_BOUND(ErrorCategory.RULE_VIOLATION, "该组件没有启用此能力。"),
    /** 目标资源不属于本 Incident 所属系统、不是 ACTIVE 或类型不受该能力支持（05 §93、06 §16 ③④）。 */
    CAPABILITY_NOT_ALLOWED(ErrorCategory.RULE_VIOLATION, "该组件当前不允许使用此能力。"),
    /** 参数不在 AI 可见 Descriptor 的受控域内（06 §22），如资源未声明的 MetricKey。 */
    CAPABILITY_ARGUMENT_INVALID(ErrorCategory.RULE_VIOLATION, "能力参数不在允许的范围内。"),
    /** 比较窗口的总范围超过 60 分钟（06 §42）：LAST_60_MIN 与前一窗口比较一律拒绝。 */
    METRIC_COMPARISON_WINDOW_EXCEEDS_LIMIT(ErrorCategory.RULE_VIOLATION, "指标比较窗口超过允许的总范围。"),
    /** 同指纹的调用仍在进行或刚在保护窗口内结束（06 §124、07 §57）：不建调用、不扣预算。 */
    CAPABILITY_DUPLICATE_REQUEST(ErrorCategory.CONFLICT, "相同的查询刚刚执行过或仍在执行。"),
    /** 已准入的调用执行失败（05 §93、§95）：调用记为 FAILED，调查继续，不是 500。 */
    CAPABILITY_INVOCATION_FAILED(ErrorCategory.DEPENDENCY_UNAVAILABLE, "能力调用失败。"),
    /*
     * 06 §35 统一 Provider 错误类型：只作为 capability_invocation.error_code 记录一次调用为什么失败（05 §95：调用 FAILED、调查继续），
     * 不由公开 API 直接返回。
     */
    /** 无法连接 Provider（拒绝连接、无法解析主机等）。 */
    CONNECTION_FAILED(ErrorCategory.DEPENDENCY_UNAVAILABLE, "无法连接数据源。"),
    /** 在该能力的配置超时内没有完成（06 §46、§125）。 */
    TIMEOUT(ErrorCategory.DEPENDENCY_TIMEOUT, "数据源响应超时。"),
    AUTHENTICATION_FAILED(ErrorCategory.DEPENDENCY_UNAVAILABLE, "数据源拒绝了认证。"),
    AUTHORIZATION_DENIED(ErrorCategory.DEPENDENCY_UNAVAILABLE, "数据源拒绝了该查询的权限。"),
    /** 受信绑定或连接配置不可用于执行（如端点不合法、模板返回多条序列）；属配置问题，不是 AI 参数问题。 */
    INVALID_BINDING(ErrorCategory.INTERNAL, "数据源绑定配置不可用。"),
    /** Provider 拒绝了由受信模板生成的查询。 */
    QUERY_REJECTED(ErrorCategory.DEPENDENCY_UNAVAILABLE, "数据源拒绝了该查询。"),
    PROVIDER_RESPONSE_INVALID(ErrorCategory.DEPENDENCY_INVALID_RESPONSE, "数据源返回的内容无法识别。"),
    /** 响应超过配置的大小上限，未读取完。 */
    RESULT_TOO_LARGE(ErrorCategory.DEPENDENCY_INVALID_RESPONSE, "数据源返回的结果过大。"),
    PROVIDER_UNAVAILABLE(ErrorCategory.DEPENDENCY_UNAVAILABLE, "数据源暂不可用。"),
    /** 调查阶段 AI 请求了不允许的意图（06 §103），如写能力。 */
    AI_INTENT_NOT_ALLOWED(ErrorCategory.RULE_VIOLATION, "当前阶段不允许该操作。"),
    /**
     * 资源上没有可执行该 Capability 的 ACTIVE Provider Binding，或唯一候选的选择器不可用（06 §16 ⑥、§18，08 TASK-046）。
     */
    CAPABILITY_PROVIDER_NOT_CONFIGURED(ErrorCategory.RULE_VIOLATION, "该组件没有配置可用于此能力的数据源。"),
    /** 同一资源与 Capability 存在多于一个 ACTIVE Provider Binding；V0.1 不做路由，绝不随机选择（06 §18）。 */
    CAPABILITY_PROVIDER_AMBIGUOUS(ErrorCategory.RULE_VIOLATION, "该组件为此能力配置了多个数据源，无法确定使用哪一个。"),
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
