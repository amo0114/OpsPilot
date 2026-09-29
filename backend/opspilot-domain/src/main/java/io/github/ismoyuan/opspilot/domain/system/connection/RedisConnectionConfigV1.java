package io.github.ismoyuan.opspilot.domain.system.connection;

import java.util.regex.Pattern;

/**
 * Redis 连接配置 redis.connection.config / 1（06 §63、07 §63）：只含 ACL 用户名，口令只经连接的 credentialRef 解析。空对象表示不认证；
 * 有用户名时必须有 credentialRef（由 Provider 校验）。
 *
 * @param username ACL 用户名，可为空（仅口令认证或不认证）
 */
public record RedisConnectionConfigV1(String username) {

    public static final String SCHEMA_NAME = "redis.connection.config";
    public static final int SCHEMA_VERSION = 1;

    /** 可见 ASCII，不含空白。 */
    private static final Pattern USERNAME = Pattern.compile("[!-~]{1,128}");

    public RedisConnectionConfigV1 {
        if (username != null && !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username is invalid");
        }
    }
}
