package io.github.ismoyuan.opspilot.domain.system.binding;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 数据库资源对应的 MySQL schema（06 §70～§78）；database.inspect 以它限定摘要与慢查询范围，不读取业务表。
 *
 * @param databaseName 未加引号的 MySQL 标识符
 */
public record MySqlResourceBindingV1(String databaseName) {

    public static final String SCHEMA_NAME = "mysql.resource.binding";
    public static final int SCHEMA_VERSION = 1;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]{1,64}");

    public MySqlResourceBindingV1 {
        Objects.requireNonNull(databaseName, "databaseName");
        if (!IDENTIFIER.matcher(databaseName).matches()) {
            throw new IllegalArgumentException("databaseName is invalid");
        }
    }
}
