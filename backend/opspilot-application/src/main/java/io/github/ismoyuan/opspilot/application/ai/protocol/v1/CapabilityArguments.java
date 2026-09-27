package io.github.ismoyuan.opspilot.application.ai.protocol.v1;

/**
 * REQUEST_CAPABILITY 的强类型参数（06 §21），具体子类型由所在 {@link RequestCapability} 变体的 capabilityKey 决定；
 * 不存在 Map 形式的万能参数，也没有 service.restart 参数。
 */
public sealed interface CapabilityArguments
        permits MetricsQueryArgumentsV1,
                LogsSearchArgumentsV1,
                DatabaseInspectArgumentsV1,
                CacheInspectArgumentsV1,
                QueueInspectArgumentsV1,
                ServiceInspectArgumentsV1 {}
