package io.github.ismoyuan.opspilot.domain.system.binding;

/**
 * 资源在某类外部系统中的强类型定位（06 §19）：ResourceBinding 选择器载荷按其 Schema 解码后的封闭类型。由受信配置提供，不由 AI 生成；
 * Provider 解析（TASK-046）只产出这些类型之一，不以 Map 传递。
 */
public sealed interface ResourceSelector
        permits PrometheusResourceBindingV1,
                LokiResourceBindingV1,
                RedisResourceBindingV1,
                MySqlResourceBindingV1,
                DockerResourceBindingV1 {}
