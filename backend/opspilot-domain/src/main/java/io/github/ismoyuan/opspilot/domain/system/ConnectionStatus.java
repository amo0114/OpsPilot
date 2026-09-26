package io.github.ismoyuan.opspilot.domain.system;

/** 数据源连接配置状态；只有 ACTIVE 连接可以成为 Provider Binding（06 §18）。 */
public enum ConnectionStatus {
    ACTIVE,
    DISABLED,
    ARCHIVED
}
