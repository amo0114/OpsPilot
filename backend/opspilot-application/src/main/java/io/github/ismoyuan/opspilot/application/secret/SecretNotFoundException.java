package io.github.ismoyuan.opspilot.application.secret;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.Map;

/**
 * 凭据无法解析。消息只含已通过格式校验的引用名（如 env://OPSPILOT_X），不含凭据值；
 * 格式不合法的引用本身可能是误填的明文，因此不回显。不放入 details，避免返回给调用方。
 */
public class SecretNotFoundException extends ApplicationException {

    public enum Reason {
        /** 引用格式合法，但对应凭据不存在或为空。 */
        NOT_FOUND,
        /** 引用为空、scheme 不受支持或键名不合法。 */
        UNSUPPORTED_REF
    }

    private final Reason reason;

    public SecretNotFoundException(Reason reason, String message) {
        super(ErrorCode.SECRET_NOT_FOUND, message, Map.of());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
