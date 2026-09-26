package io.github.ismoyuan.opspilot.domain.system;

/** V0.1 系统组件类型（03 §8、04 §8）；Capability 按类型声明适用范围。 */
public enum ResourceType {
    SERVICE,
    DATABASE,
    CACHE,
    MESSAGE_QUEUE,
    CONSUMER,
    EXTERNAL_DEPENDENCY
}
