package io.github.ismoyuan.opspilot.domain.system;

/** 数据源连接的外部系统类型（04 §9）；每个 Capability 在 Registry 中声明支持的 ProviderType（06 §14、§17）。 */
public enum ProviderType {
    PROMETHEUS,
    LOKI,
    REDIS,
    MYSQL,
    DOCKER
}
