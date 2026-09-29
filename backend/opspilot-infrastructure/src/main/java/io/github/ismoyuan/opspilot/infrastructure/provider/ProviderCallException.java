package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;

/** Provider 取数中的预期失败（06 §35）；在 Provider 边界转为 {@link ProviderOutcome.Failed}，不向执行服务抛出。 */
final class ProviderCallException extends RuntimeException {

    private final ErrorCode code;

    /** @param safeMessage 固定文案，不含端点、凭据、查询或响应内容 */
    ProviderCallException(ErrorCode code, String safeMessage) {
        super(safeMessage, null, false, false);
        this.code = code;
    }

    ProviderOutcome.Failed outcome() {
        return new ProviderOutcome.Failed(code, getMessage());
    }

    ErrorCode code() {
        return code;
    }
}
