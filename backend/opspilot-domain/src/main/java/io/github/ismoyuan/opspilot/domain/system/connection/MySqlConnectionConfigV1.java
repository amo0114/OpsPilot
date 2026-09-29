package io.github.ismoyuan.opspilot.domain.system.connection;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * MySQL 连接配置 mysql.connection.config / 1（06 §73、07 §63）：只读调查账号的用户名；口令只经连接的 credentialRef 解析。
 *
 * @param username 只读账号名
 */
public record MySqlConnectionConfigV1(String username) {

    public static final String SCHEMA_NAME = "mysql.connection.config";
    public static final int SCHEMA_VERSION = 1;

    /** MySQL 账号名上限 32 字符；可见 ASCII，不含空白与引号。 */
    private static final Pattern USERNAME = Pattern.compile("[!-~&&[^'\"`\\\\]]{1,32}");

    public MySqlConnectionConfigV1 {
        Objects.requireNonNull(username, "username");
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username is invalid");
        }
    }
}
