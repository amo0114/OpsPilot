package io.github.ismoyuan.opspilot.application.capability;

import io.github.ismoyuan.opspilot.domain.capability.CapabilitySchema;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.List;
import java.util.Objects;

/** 一次已准入调用的执行结果（06 §33～§35）：成功时是已结构化、已脱敏的结果与 Observation 草稿，失败时只有错误。 */
public sealed interface InvocationOutcome {

    /**
     * @param resultPayload 结果 Schema 下的已脱敏结构化结果（06 §119）
     * @param rawResultRef 较大脱敏原始结果的引用（file://，TASK-050），可为空
     */
    record Succeeded(
            CapabilitySchema resultSchema,
            String resultPayload,
            String rawResultRef,
            List<ObservationDraft> observations)
            implements InvocationOutcome {

        public Succeeded {
            Objects.requireNonNull(resultSchema, "resultSchema");
            Objects.requireNonNull(resultPayload, "resultPayload");
            observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        }
    }

    /** @param safeMessage 固定或已脱敏文案，不含凭据、端点或原始返回 */
    record Failed(ErrorCode errorCode, String safeMessage) implements InvocationOutcome {

        public Failed {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(safeMessage, "safeMessage");
        }
    }
}
