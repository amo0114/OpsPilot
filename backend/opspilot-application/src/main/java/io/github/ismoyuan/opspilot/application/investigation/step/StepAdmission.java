package io.github.ismoyuan.opspilot.application.investigation.step;

import io.github.ismoyuan.opspilot.domain.agentstep.AgentStep;
import io.github.ismoyuan.opspilot.domain.investigation.StepAdmissionRejection;
import java.time.Duration;
import java.util.Objects;

/** 单步准入结果（08 TASK-039）：准入即已提交 RUNNING Step；拒绝时什么也没写，也不得发网络请求。 */
public sealed interface StepAdmission {

    /** @param maxWait 本次 AI 调用最多等待：min(单步超时, 本轮剩余时间)（05 §89） */
    record Admitted(AgentStep step, Duration maxWait) implements StepAdmission {

        public Admitted {
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(maxWait, "maxWait");
        }
    }

    record Rejected(StepAdmissionRejection reason) implements StepAdmission {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
