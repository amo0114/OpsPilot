package io.github.ismoyuan.opspilot.web.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/**
 * 公开 API 请求体严格反序列化：Jackson 3 默认忽略未知字段，这里改为拒绝（未知字段 → 400 REQUEST_VALIDATION_FAILED），
 * 避免拼写错误的 expectedVersion 等字段被静默丢弃；整数字段不接受小数（否则会被截断后通过校验）。
 */
@Configuration(proxyBeanMethods = false)
public class ApiJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictApiDeserialization() {
        return builder -> builder.enable(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // 小数不得截断为整数：{"expectedVersion": 0.9} 必须拒绝，而不是按 0 执行迁移
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
