package io.github.ismoyuan.opspilot.domain.system;

/** 业务系统配置状态；配置不物理删除，用状态表达停用与归档（04 §4、§7）。 */
public enum SystemStatus {
    ACTIVE,
    DISABLED,
    ARCHIVED
}
