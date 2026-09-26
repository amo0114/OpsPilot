package io.github.ismoyuan.opspilot.application.schema;

import io.github.ismoyuan.opspilot.application.error.ApplicationException;
import io.github.ismoyuan.opspilot.domain.error.ErrorCode;
import java.util.Map;

/**
 * 载荷无法按声明的 schema 解码。受信配置损坏属于内部错误；需要其他错误码的调用方（如 AI 输出）自行翻译。
 *
 * <p>不保留解析器异常作为 cause，也不放入 details：解析器文本可能带出载荷片段，details 会返回给调用方。
 */
public class SchemaPayloadException extends ApplicationException {

    public enum Reason {
        UNKNOWN_SCHEMA,
        TYPE_MISMATCH,
        INVALID_PAYLOAD
    }

    private final Reason reason;
    private final String schemaName;
    private final int schemaVersion;

    /** @param detail 只描述位置或规则（字段路径、校验名），不含载荷值 */
    public SchemaPayloadException(Reason reason, String schemaName, int schemaVersion, String detail) {
        super(
                ErrorCode.INTERNAL_ERROR,
                "Schema payload rejected: reason=" + reason + " schema=" + schemaName + "/" + schemaVersion
                        + (detail == null ? "" : " detail=" + detail),
                Map.of());
        this.reason = reason;
        this.schemaName = schemaName;
        this.schemaVersion = schemaVersion;
    }

    public Reason reason() {
        return reason;
    }

    public String schemaName() {
        return schemaName;
    }

    public int schemaVersion() {
        return schemaVersion;
    }
}
