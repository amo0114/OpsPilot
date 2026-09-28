package io.github.ismoyuan.opspilot.application.investigation.orchestration;

import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
import java.util.Objects;

/** 一次 REQUEST_CAPABILITY 的准入与执行结果。 */
public sealed interface CapabilityRequestResult {

    /** 准入被拒（Stop、旧 run、到期、额度用尽、不在调查）：不发 Provider、不建调用、不扣预算。 */
    record Rejected(StepAdmissionRejection reason) implements CapabilityRequestResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 通过准入但当前没有可执行的 Provider（TASK-048/058 之前）：没有 Observation。 */
    record NotExecuted(String reason) implements CapabilityRequestResult {
        public NotExecuted {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
