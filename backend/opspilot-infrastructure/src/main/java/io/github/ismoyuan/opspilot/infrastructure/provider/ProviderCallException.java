package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.capability.provider.ProviderOutcome;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;

/** Provider 取数中的预期失败（06 §35）；在 Provider 边界转为 {@link ProviderOutcome.Failed}，不向执行服务抛出。 */
final class ProviderCallException extends RuntimeException {

    private final ErrorCode code;
    private final boolean requestSent;

    /** @param safeMessage 固定文案，不含端点、凭据、查询或响应内容 */
    ProviderCallException(ErrorCode code, String safeMessage) {
        this(code, safeMessage, false);
    }

    /**
     * @param requestSent 失败发生在请求开始写出之后：对写操作而言远端可能已经执行（04 §82），调用方不得当作“未发送”
     */
    ProviderCallException(ErrorCode code, String safeMessage, boolean requestSent) {
        super(safeMessage, null, false, false);
        this.code = code;
        this.requestSent = requestSent;
    }

    ProviderOutcome.Failed outcome() {
        return new ProviderOutcome.Failed(code, getMessage());
    }

    ErrorCode code() {
        return code;
    }

    boolean requestSent() {
        return requestSent;
    }
}
