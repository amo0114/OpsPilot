package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;

/** 一次调查 OBSERVE 调用的准入结果（08 TASK-048）；除 Admitted 外均未建 Invocation、未扣预算。 */
public sealed interface CapabilityAdmission {

    /** 调查状态不允许：不在调查、旧 run、已 Stop、已到截止或本轮额度用尽。 */
    record NotAdmitted(StepAdmissionRejection reason) implements CapabilityAdmission {}

    /** Capability 规则拒绝：Registry、资源、绑定、Provider、参数或 Duplicate。 */
    record Rejected(ErrorCode code, String reason) implements CapabilityAdmission {}

    record Admitted(AdmittedInvocation invocation) implements CapabilityAdmission {}
}
