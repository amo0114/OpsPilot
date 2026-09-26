package io.github.ismoyuan.opspilot.domain.capability;

/** Capability 读写性质（06 §11）；由 Java 显式定义，不由 AI 运行时判断。CHANGE 必须人工审批（06 §12）。 */
public enum CapabilityMode {
    OBSERVE,
    CHANGE
}
