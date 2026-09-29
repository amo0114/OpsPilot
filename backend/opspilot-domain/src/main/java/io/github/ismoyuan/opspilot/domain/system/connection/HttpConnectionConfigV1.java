package io.github.ismoyuan.opspilot.domain.system.connection;

/**
 * HTTP 数据源（Prometheus、Loki）的连接配置 prometheus.connection.config / 1 与 loki.connection.config / 1（06 §19～§20、07 §63）：
 * 只描述认证方式，凭据本身只以连接的 credentialRef 出现、由 SecretResolver 在建立请求时解析，不写入配置。
 *
 * <p>空对象表示不认证。authScheme 与 credentialRef 必须同时存在或同时不存在（由 Provider 校验，不猜测认证方式）；BASIC 需要 username。
 *
 * @param authScheme 认证方式；不认证时为空
 * @param username BASIC 的用户名；其他方式为空
 */
public record HttpConnectionConfigV1(AuthScheme authScheme, String username) {

    public static final String PROMETHEUS_SCHEMA_NAME = "prometheus.connection.config";
    public static final String LOKI_SCHEMA_NAME = "loki.connection.config";
    public static final int SCHEMA_VERSION = 1;

    public enum AuthScheme {
        /** Authorization: Bearer &lt;credential&gt; */
        BEARER,
        /** Authorization: Basic base64(username:credential) */
        BASIC
    }

    public HttpConnectionConfigV1 {
        if (authScheme == AuthScheme.BASIC) {
            if (username == null || username.isBlank() || username.contains(":")) {
                throw new IllegalArgumentException("username is required for BASIC and must not contain ':'");
            }
        } else if (username != null) {
            throw new IllegalArgumentException("username is only allowed for BASIC");
        }
    }
}
