package io.github.ismoyuan.opspilot.domain.incident;

/** Incident 的创建来源：人工创建（05 §20）或 Fault Lab 确认注入成功后创建（05 §70）。 */
public enum IncidentSource {
    MANUAL,
    FAULT_LAB
}
