package io.github.ismoyuan.opspilot.infrastructure.schema;

import io.github.ismoyuan.opspilot.application.schema.SchemaCodecRegistry;

/** 供其他测试包取得正式的 Codec 注册表（实现类为包内可见）。 */
public final class SchemaCodecs {

    private SchemaCodecs() {}

    public static SchemaCodecRegistry registry() {
        return new JacksonSchemaCodecRegistry();
    }
}
