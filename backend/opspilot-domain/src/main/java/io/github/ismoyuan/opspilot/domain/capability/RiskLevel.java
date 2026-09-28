package io.github.ismoyuan.opspilot.domain.capability;

/**
 * Capability 的默认风险等级（06 §13～§14、CAP-INV-015）：由 Java Registry 决定，不由 AI 决定。V0.1 只读能力为 LOW，唯一写能力
 * service.restart 为 MEDIUM（06 §102）；新增等级随需要它的 Task 加入。
 */
public enum RiskLevel {
    LOW,
    MEDIUM
}
