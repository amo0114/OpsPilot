package io.github.ismoyuan.opspilot.application.investigation.orchestration;

import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
import java.util.List;
import java.util.Objects;

/** 一次 REQUEST_CAPABILITY 经准入与执行后的结果（对应 CapabilityExecutionResult，供编排与审计）。 */
public sealed interface CapabilityRequestResult {

    /** 调查状态类拒绝（Stop、旧 run、到期、额度用尽、不在调查）：不建调用、不扣预算，循环由下一次准入收束。 */
    record Rejected(StepAdmissionRejection reason) implements CapabilityRequestResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Guard 拒绝（未绑定、参数不在受控域、重复等）：不建调用、不扣预算，已以时间线反馈给当前 run（06 §124）。 */
    record Refused(ErrorCode code, String reason) implements CapabilityRequestResult {
        public Refused {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 调用成功，observationIds 为本次产生的 Observation（下一步上下文可见）。 */
    record Executed(long invocationId, List<Long> observationIds) implements CapabilityRequestResult {
        public Executed {
            observationIds = List.copyOf(observationIds);
        }
    }

    /** 调用失败：只有错误记录与时间线反馈，没有 Observation。 */
    record Failed(long invocationId, ErrorCode errorCode) implements CapabilityRequestResult {
        public Failed {
            Objects.requireNonNull(errorCode, "errorCode");
        }
    }

    /** 结果落账时调用已是终态（如已被中断标记），本次结果未采用。 */
    record Discarded(long invocationId) implements CapabilityRequestResult {}
}
