package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
import java.util.List;

/** 一次调查 OBSERVE 请求的最终结果，供调查循环（TASK-058）作为结构化反馈。 */
public sealed interface CapabilityExecutionResult {

    record NotAdmitted(StepAdmissionRejection reason) implements CapabilityExecutionResult {}

    record Rejected(ErrorCode code, String reason) implements CapabilityExecutionResult {}

    /** 结果已落账为 SUCCEEDED；observationIds 可为空（成功但没有提取出 Observation）。 */
    record Succeeded(long invocationId, List<Long> observationIds) implements CapabilityExecutionResult {}

    record Failed(long invocationId, ErrorCode errorCode) implements CapabilityExecutionResult {}

    /**
     * 结果未采用（B14-R1）：落账时 Invocation 已是终态（如已被中断标记为 PROCESS_INTERRUPTED），本次结果没有写入；调用方应按该
     * Invocation 的已有终态处理，不能当作成功或失败的新事实。
     */
    record Discarded(long invocationId) implements CapabilityExecutionResult {}
}
