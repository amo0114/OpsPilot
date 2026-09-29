package io.github.ismoyuan.opspilot.application.capability.provider;

import io.github.ismoyuan.opspilot.application.capability.result.CapabilityResult;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.time.Instant;
import java.util.Objects;

/** Provider 一次取数的结果：真实的结构化结果（尚未脱敏）或失败。 */
public sealed interface ProviderOutcome {

    /**
     * @param rawResult 较大的原始返回，按行存放（每个点、每条日志一行，06 §32、B15 头部规则说明），经管线脱敏后另存；没有时为空
     * @param observedAt 取得数据的真实时刻
     */
    record Fetched(CapabilityResult result, String rawResult, Instant observedAt) implements ProviderOutcome {

        public Fetched {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    /**
     * @param errorCode 06 §35 Provider 错误类型
     * @param safeMessage 固定文案，不含端点、凭据、查询文本或响应内容
     */
    record Failed(ErrorCode errorCode, String safeMessage) implements ProviderOutcome {

        public Failed {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(safeMessage, "safeMessage");
        }
    }
}
