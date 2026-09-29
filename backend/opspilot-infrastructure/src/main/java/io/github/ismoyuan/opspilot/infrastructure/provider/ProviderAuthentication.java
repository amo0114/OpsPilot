package io.github.ismoyuan.opspilot.infrastructure.provider;

import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;
import io.github.ismoyuan.opspilot.application.schema.SchemaPayloadException;
import io.github.ismoyuan.opspilot.application.secret.SecretNotFoundException;
import io.github.ismoyuan.opspilot.application.secret.SecretResolver;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import io.github.ismoyuan.opspilot.domain.system.DataSourceConnection;
import io.github.ismoyuan.opspilot.domain.system.connection.HttpConnectionConfigV1;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 数据源连接配置与凭据（06 §20、07 §63，B16-R1；B17 起 Redis/MySQL 共用配置解码与凭据解析）。HTTP 数据源的认证：按连接配置（{@link HttpConnectionConfigV1}）确定认证方式，凭据只经连接的 credentialRef 由
 * {@link SecretResolver} 在发请求前解析，只放入该次请求的 Authorization 头，不进入日志、错误文案、结果或持久化。
 *
 * <p>配置与凭据引用必须一致：都没有为不认证；只有其一为 INVALID_BINDING（不猜测认证方式）；引用无法解析为 SECRET_NOT_FOUND。
 */
final class ProviderAuthentication {

    private final SchemaCodecRegistry codecs;
    private final SecretResolver secrets;

    ProviderAuthentication(SchemaCodecRegistry codecs, SecretResolver secrets) {
        this.codecs = codecs;
        this.secrets = secrets;
    }

    /**
     * @param expectedSchemaName 该 Provider 的连接配置 Schema（prometheus/loki.connection.config）
     * @return Authorization 头的值；不认证时为空
     * @throws ProviderCallException INVALID_BINDING 或 SECRET_NOT_FOUND
     */
    String authorization(DataSourceConnection connection, String expectedSchemaName) {
        HttpConnectionConfigV1 config = config(connection, expectedSchemaName, HttpConnectionConfigV1.class);
        String reference = connection.credentialRef();
        if (config.authScheme() == null && reference == null) {
            return null;
        }
        if (config.authScheme() == null || reference == null) {
            throw new ProviderCallException(
                    ErrorCode.INVALID_BINDING,
                    "Connection authentication scheme and credential must be configured together");
        }
        String credential = credential(connection);
        return switch (config.authScheme()) {
            case BEARER -> "Bearer " + credential;
            case BASIC ->
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (config.username() + ":" + credential).getBytes(StandardCharsets.UTF_8));
        };
    }

    /**
     * 按该 Provider 的连接配置 Schema 解码连接配置（version 1）。
     *
     * @throws ProviderCallException INVALID_BINDING
     */
    <T> T config(DataSourceConnection connection, String expectedSchemaName, Class<T> type) {
        if (!connection.configSchema().name().equals(expectedSchemaName)
                || connection.configSchema().version() != 1) {
            throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Connection config schema is not supported");
        }
        try {
            return codecs.decode(expectedSchemaName, 1, connection.configPayload(), type);
        } catch (SchemaPayloadException ex) {
            throw new ProviderCallException(ErrorCode.INVALID_BINDING, "Connection config is invalid");
        }
    }

    /**
     * 解析连接的 credentialRef；只在建立连接的那一刻取值。
     *
     * @return 明文凭据；连接没有 credentialRef 时为空
     * @throws ProviderCallException SECRET_NOT_FOUND（引用无法解析）或 INVALID_BINDING（含控制字符，不能安全放入协议）
     */
    String credential(DataSourceConnection connection) {
        if (connection.credentialRef() == null) {
            return null;
        }
        String credential;
        try {
            credential = secrets.resolve(connection.credentialRef()).reveal();
        } catch (SecretNotFoundException ex) {
            throw new ProviderCallException(ErrorCode.SECRET_NOT_FOUND, "Connection credential is not available");
        }
        if (credential.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            throw new ProviderCallException(
                    ErrorCode.INVALID_BINDING, "Connection credential contains control characters");
        }
        return credential;
    }
}
