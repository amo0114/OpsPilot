package io.github.ismoyuan.opspilot.domain.faultlab;

/**
 * 一次故障演练的状态（04 §63、09 §13～§18）。INJECTING 只在注入并确认生效之前；确认生效后与 Incident 创建同事务进入 ACTIVE，未确认生效
 * 为 FAILED（不创建 Incident）。Reset 只恢复实验环境，不改变 Incident（05 §72）。
 */
public enum FaultExperimentStatus {
    INJECTING,
    ACTIVE,
    RESETTING,
    RESET,
    FAILED;

    /** 实验环境可能仍带有注入的故障，可以执行 Reset。 */
    public boolean resettable() {
        return this == ACTIVE || this == FAILED;
    }

    /** 正在注入、故障生效中或正在恢复：同一系统同时最多一个，新的注入与其他实验的 Reset 都须等待。 */
    public boolean inProgress() {
        return this == INJECTING || this == ACTIVE || this == RESETTING;
    }
}
