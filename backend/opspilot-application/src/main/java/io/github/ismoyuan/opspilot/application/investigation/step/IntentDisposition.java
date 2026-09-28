package io.github.ismoyuan.opspilot.application.investigation.step;

import java.util.Objects;

/**
 * 一次合法 AI 输出被如何处置（05 §83、07 §43）：与 Step 一起记入审计，“原始输出”与“被业务接受”分开保存（04 §59）。
 *
 * @param code REJECTED 时为拒绝码（如 EVIDENCE_LINK_ALREADY_EXISTS），其他为空
 * @param reason REJECTED 时为结构化原因（如 NOT_IN_INVESTIGATION、OBSERVATION_OUT_OF_SCOPE），可为空；不含输入值
 */
public record IntentDisposition(Outcome outcome, String code, String reason) {

    public enum Outcome {
        /** 领域写入已在同一事务完成（Hypothesis、Evidence、Diagnosis）。 */
        APPLIED,
        /** REQUEST_CAPABILITY：交给提交后的 Capability 准入，本事务无领域写入。 */
        ACCEPTED,
        /** 业务规则拒绝：领域写入已回滚，只留审计。 */
        REJECTED,
        /** Step 所属 run 已不是当前 run，或 Incident 已不在调查：只审计，不能驱动领域写入（BND-015）。 */
        NOT_CURRENT,
        /** 同轮已 Stop：除 COMPLETE_INVESTIGATION 外只审计，不再展开下一步（07 §43）。 */
        STOPPED
    }

    public IntentDisposition {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static IntentDisposition of(Outcome outcome) {
        return new IntentDisposition(outcome, null, null);
    }

    public static IntentDisposition rejected(String code, String reason) {
        return new IntentDisposition(Outcome.REJECTED, Objects.requireNonNull(code, "code"), reason);
    }
}
