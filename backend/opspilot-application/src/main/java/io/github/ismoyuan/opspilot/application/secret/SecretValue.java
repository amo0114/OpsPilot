package io.github.ismoyuan.opspilot.application.secret;

import java.util.Objects;

/** 已解析的凭据。不是 record：toString/equals 不暴露或比较明文，只有 reveal() 取值。 */
public final class SecretValue {

    private final String value;

    public SecretValue(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** 仅在建立外部连接的那一刻调用。 */
    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "SecretValue[***]";
    }
}
